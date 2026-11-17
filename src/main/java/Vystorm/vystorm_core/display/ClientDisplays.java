package Vystorm.vystorm_core.display;

import Vystorm.vystorm_core.display.internal.DisplayPackets;
import Vystorm.vystorm_core.display.internal.IdPool;
import Vystorm.vystorm_core.display.internal.MapTiles;
import Vystorm.vystorm_core.display.internal.WallGeometry;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Client-only displays: walls of item frames with maps that exist only as packets for nearby players, so a billboard
 * costs no server entities, no entity ticking and no server map rendering. Works without ProtocolLib or PacketEvents
 * on Paper/Purpur 1.21.8 to 26.2 (one reflective adapter, probed at first use: {@link #supported()}).
 *
 * <p>Viewers: players in the wall's world within {@link MapWall.Spec#viewDistance()} blocks whose client has the frame's
 * chunk. Frames are re-sent after chunk re-delivery, respawn and world change and dropped on quit. Map colours go out as
 * the changed rectangle per tile, limited per tick ({@link #budgets}). Clicks arrive through Paper's
 * {@code PlayerUseUnknownEntityEvent}. Walls are removed when their plugin disables. Server thread only. @since 0.44.0
 * <p>Preferred access: {@code api.packages().clientDisplays()}.
 */
public final class ClientDisplays {
    /** First client map id Core uses; ids up to {@link Integer#MAX_VALUE} are reserved and never refer to a real map. */
    public static final int MAP_ID_FIRST = 0x7F000000;
    public static final int MAP_ID_COUNT = Integer.MAX_VALUE - MAP_ID_FIRST + 1;

    /** Counters since start; bytes are uncompressed packet payloads measured with the server's own codecs at probe time. */
    public record Stats(int walls, int viewers, int fakeFrames, long spawnPackets, long dataPackets, long removePackets,
                        long mapPackets, long mapPixels, long estimatedBytes, long clicks, long deferredTicks, Map<String, Integer> packetSizes,
                        int mapIdsInUse, int entityIdsInUse, long ticks, long tickNanos, long maxTickNanos) {}

    private static final Set<MapWall> WALLS = new LinkedHashSet<>();
    private static final Map<String, MapWall> BY_ENTITY = new HashMap<>();
    private static final IdPool MAP_IDS = new IdPool(MAP_ID_FIRST, MAP_ID_COUNT);
    private static final Set<UUID> RECHECK = new HashSet<>();
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();
    private static DisplayPackets packets;
    private static Map<String, Integer> sizes = Map.of();
    private static String unsupported;
    private static boolean probed;
    private static Plugin core;
    private static BukkitTask task;
    private static int perPlayer = 8, perTick = 64;
    /** Frame spawns per player and tick (spawn + metadata packets are tiny; this only smooths big arrivals). */
    private static final int SPAWNS_PER_PLAYER_TICK = 128;
    private static long tick, cursor;
    private static long spawnPackets, dataPackets, removePackets, mapPackets, mapPixels, bytes, clicks, deferred;
    private static long busyTicks, tickNanos, maxTickNanos;

    private ClientDisplays() {}

    /** True when this server can show client-only displays (packet adapter built and its packets encode). */
    public static boolean supported() { thread(); probe(); return packets != null; }

    /** Why {@link #supported()} is false; empty when supported. */
    public static Optional<String> unsupportedReason() { thread(); probe(); return Optional.ofNullable(unsupported); }

    /** A new client-only map wall, or empty when unsupported or the owner is disabled. */
    public static Optional<MapWall> mapWall(Plugin owner, MapWall.Spec spec) {
        thread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spec, "spec");
        if (!owner.isEnabled() || !supported()) return Optional.empty();
        start();
        int tiles = spec.width() * spec.height();
        int[] entityIds = new int[tiles], mapIds = new int[tiles];
        try {
            for (int i = 0; i < tiles; i++) { entityIds[i] = packets.nextEntityId(spec.world()); mapIds[i] = MAP_IDS.take(); }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientDisplays", owner.getName(), "create", failure);
            return Optional.empty();
        }
        MapWall wall = new MapWall(owner, spec, entityIds, mapIds);
        WALLS.add(wall);
        for (int id : entityIds) BY_ENTITY.put(key(spec.world().getUID(), id), wall);
        for (Player player : spec.world().getPlayers()) RECHECK.add(player.getUniqueId());
        return Optional.of(wall);
    }

    /** Removes every wall of the plugin (also done automatically when it disables). */
    public static void release(Plugin owner) {
        thread();
        for (MapWall wall : List.copyOf(WALLS)) if (wall.owner == owner) remove(wall);
    }

    /** Map packets per player and in total per tick (defaults 8 and 64). A full tile is 16 KiB before compression. */
    public static void budgets(int mapsPerPlayerPerTick, int mapsPerTick) {
        thread();
        perPlayer = Math.clamp(mapsPerPlayerPerTick, 1, 256);
        perTick = Math.clamp(mapsPerTick, perPlayer, 4096);
    }

    public static Stats stats() {
        thread();
        int viewers = 0, frames = 0;
        for (MapWall wall : WALLS) for (MapWall.Viewer v : wall.viewers.values()) { viewers++; for (boolean s : v.shown) if (s) frames++; }
        return new Stats(WALLS.size(), viewers, frames, spawnPackets, dataPackets, removePackets, mapPackets, mapPixels, bytes, clicks, deferred, sizes,
                MAP_IDS.used(), BY_ENTITY.size(), busyTicks, tickNanos, maxTickNanos);
    }

    // ------------------------------------------------------------------ internals

    static void thread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ClientDisplays requires the server thread"); }

    private static void probe() {
        if (probed) return;
        probed = true;
        try {
            if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("no world loaded yet");
            DisplayPackets candidate = new DisplayPackets();
            sizes = Map.copyOf(candidate.probe(Bukkit.getWorlds().getFirst()));
            packets = candidate;
            Bukkit.getLogger().info("[Vystorm Core] Client displays ready on " + Bukkit.getName() + " " + Vystorm.vystorm_core.compat.PlatformCompat.getMinecraftVersion()
                    + " (encoded packet bytes " + sizes + ")");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unsupported = Bukkit.getName() + " " + Vystorm.vystorm_core.compat.PlatformCompat.getMinecraftVersion() + ": " + failure;
            Bukkit.getLogger().log(Level.INFO, "[Vystorm Core] Client displays unavailable (" + unsupported + "); plugins fall back to real entities.");
            if (Bukkit.getWorlds().isEmpty()) probed = false; // try again once a world exists
        }
    }

    private static void start() {
        if (task != null) return;
        core = JavaPlugin.getProvidingPlugin(ClientDisplays.class);
        Bukkit.getPluginManager().registerEvents(new Events(), core);
        task = Bukkit.getScheduler().runTaskTimer(core, ClientDisplays::tick, 1, 1);
    }

    static void recheck(Player player) { RECHECK.add(player.getUniqueId()); }

    private static String key(UUID world, int entityId) { return world + ":" + entityId; }

    static void remove(MapWall wall) {
        if (wall.removed) return;
        wall.removed = true;
        WALLS.remove(wall);
        for (MapWall.Viewer viewer : wall.viewers.values()) hide(wall, viewer, all(wall));
        wall.viewers.clear();
        wall.own.clear();
        wall.shared = null;
        for (int id : wall.entityIds) BY_ENTITY.remove(key(wall.spec.world().getUID(), id));
        for (int id : wall.mapIds) MAP_IDS.give(id);
    }

    private static void forget(Player player) {
        for (MapWall wall : WALLS) wall.viewers.remove(player.getUniqueId());
        RECHECK.add(player.getUniqueId());
    }

    private static void tick() {
        tick++;
        if (WALLS.isEmpty()) return;
        long started = System.nanoTime();
        for (MapWall wall : List.copyOf(WALLS)) if (!wall.owner.isEnabled()) remove(wall);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!RECHECK.remove(player.getUniqueId()) && (tick + (player.getEntityId() & 3)) % 4 != 0) continue;
            // between worlds or while leaving Paper has no chunk loader for the player and isChunkSent throws: next tick
            try { visibility(player); } catch (RuntimeException inTransition) { RECHECK.add(player.getUniqueId()); }
        }
        flush();
        long spent = System.nanoTime() - started;
        busyTicks++;
        tickNanos += spent;
        maxTickNanos = Math.max(maxTickNanos, spent);
    }

    private static void visibility(Player player) {
        Location at = player.getLocation();
        int spawns = 0;
        for (MapWall wall : List.copyOf(WALLS)) {
            MapWall.Spec spec = wall.spec;
            MapWall.Viewer viewer = wall.viewers.get(player.getUniqueId());
            boolean near = player.isOnline() && at.getWorld().equals(spec.world())
                    && WallGeometry.distanceSquared(spec.x(), spec.y(), spec.z(), spec.facing().getModX(), spec.facing().getModZ(),
                    spec.width(), spec.height(), at.getX(), at.getY(), at.getZ()) <= spec.viewDistance() * spec.viewDistance();
            if (!near) {
                if (viewer != null) { hide(wall, viewer, all(wall)); wall.viewers.remove(player.getUniqueId()); }
                wall.own.remove(player.getUniqueId());
                continue;
            }
            if (viewer == null) { viewer = new MapWall.Viewer(player, wall.entityIds.length); wall.viewers.put(player.getUniqueId(), viewer); }
            List<Integer> gone = new ArrayList<>();
            for (int i = 0; i < wall.entityIds.length; i++) {
                boolean sent = Vystorm.vystorm_core.compat.ClientEvents.sent(player, wall.chunks[i]);
                if (sent && !viewer.shown[i]) {
                    // a viewer arriving at a big field gets its frames over a few ticks, not all in one
                    if (spawns++ >= SPAWNS_PER_PLAYER_TICK) { RECHECK.add(player.getUniqueId()); continue; }
                    show(wall, viewer, i);
                    if (wall.removed) break;
                }
                else if (!sent && viewer.shown[i]) gone.add(i);
            }
            if (!gone.isEmpty() && !wall.removed) hide(wall, viewer, gone.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private static void show(MapWall wall, MapWall.Viewer viewer, int tile) {
        int[] b = wall.blocks[tile];
        try {
            if (wall.spawnPackets[tile] == null) wall.spawnPackets[tile] = packets.spawnFrame(wall.entityIds[tile], wall.uuids[tile], b[0], b[1], b[2], wall.spec.facing(), wall.spec.glow());
            if (wall.dataPackets[tile] == null) wall.dataPackets[tile] = packets.frameData(wall.entityIds[tile], wall.mapIds[tile], wall.spec.invisible());
            packets.send(viewer.player, wall.spawnPackets[tile]);
            packets.send(viewer.player, wall.dataPackets[tile]);
            spawnPackets++; dataPackets++;
            bytes += sizes.getOrDefault("spawn", 0) + sizes.getOrDefault("data", 0);
            viewer.shown[tile] = true;
            viewer.markFull(tile);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            fail(wall, "spawn", failure);
        }
    }

    private static void hide(MapWall wall, MapWall.Viewer viewer, int[] tiles) {
        if (!viewer.player.isOnline()) return;
        int[] ids = Arrays.stream(tiles).map(i -> wall.entityIds[i]).toArray();
        for (int i : tiles) {
            viewer.shown[i] = false;
            if (viewer.pending[i] != null) { viewer.pending[i] = null; viewer.pendingCount--; }
        }
        if (ids.length == 0) return;
        try {
            packets.send(viewer.player, packets.remove(ids));
            removePackets++;
            bytes += sizes.getOrDefault("remove", 0) + 4L * (ids.length - 1);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientDisplays", wall.owner.getName(), "remove", failure);
        }
    }

    private static void flush() {
        List<MapWall.Viewer> queue = new ArrayList<>();
        List<MapWall> owners = new ArrayList<>();
        for (MapWall wall : WALLS) for (MapWall.Viewer v : wall.viewers.values()) if (v.pendingCount > 0) { queue.add(v); owners.add(wall); }
        if (queue.isEmpty()) return;
        int start = (int) (cursor++ % queue.size());
        int total = perTick;
        Map<UUID, Integer> used = new HashMap<>();
        for (int n = 0; n < queue.size(); n++) {
            if (total <= 0) { deferred++; return; }
            int k = (start + n) % queue.size();
            MapWall wall = owners.get(k);
            MapWall.Viewer viewer = queue.get(k);
            byte[] pixels = wall.source(viewer);
            if (pixels == null) continue;
            int tiles = wall.entityIds.length;
            for (int step = 0; step < tiles && viewer.pendingCount > 0 && total > 0; step++) {
                int mine = used.getOrDefault(viewer.player.getUniqueId(), 0);
                if (mine >= perPlayer) break;
                int i = (viewer.cursor + step) % tiles;
                int[] rect = viewer.pending[i];
                if (rect == null) continue;
                try {
                    byte[] colours = MapTiles.region(pixels, wall.spec.pixelWidth(), i % wall.spec.width(), i / wall.spec.width(), rect);
                    packets.send(viewer.player, packets.mapData(wall.mapIds[i], rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], colours));
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    fail(wall, "map", failure);
                    return;
                }
                viewer.pending[i] = null;
                viewer.pendingCount--;
                viewer.cursor = (i + 1) % tiles;
                mapPackets++;
                mapPixels += (long) (rect[2] - rect[0]) * (rect[3] - rect[1]);
                bytes += sizes.getOrDefault("map-empty", 0) - 1 + (long) (rect[2] - rect[0]) * (rect[3] - rect[1]);
                used.merge(viewer.player.getUniqueId(), 1, Integer::sum);
                total--;
            }
        }
    }

    private static void fail(MapWall wall, String operation, Throwable failure) {
        ProviderFailures.report("ClientDisplays", wall.owner.getName(), operation, failure);
        remove(wall);
    }

    private static int[] all(MapWall wall) {
        int[] tiles = new int[wall.entityIds.length];
        for (int i = 0; i < tiles.length; i++) tiles[i] = i;
        return tiles;
    }

    private static void click(Player player, int entityId, boolean attack, EquipmentSlot hand) {
        MapWall wall = BY_ENTITY.get(key(player.getWorld().getUID(), entityId));
        if (wall == null || wall.removed || wall.clicks == null || !wall.isViewing(player)) return;
        if (!attack && hand == EquipmentSlot.OFF_HAND) return;
        // A right click arrives as INTERACT_AT and INTERACT in the same tick: report it once.
        Long last = LAST_CLICK.put(player.getUniqueId(), tick);
        if (last != null && tick - last < 2) return;
        int tile = -1;
        for (int i = 0; i < wall.entityIds.length; i++) if (wall.entityIds[i] == entityId) tile = i;
        MapWall.Spec spec = wall.spec;
        int tx = tile % spec.width(), ty = tile / spec.width();
        Location eye = player.getEyeLocation();
        // Same reach rule as vanilla for real entities: interaction range plus a 3 block buffer, to the frame's block.
        double reach = reach(player) + 3;
        int[] block = wall.blocks[tile];
        if (WallGeometry.distanceSquared(block[0], block[1], block[2], spec.facing().getModX(), spec.facing().getModZ(), 1, 1,
                eye.getX(), eye.getY(), eye.getZ()) > reach * reach) return;
        Vector d = eye.getDirection();
        int[] pixel = WallGeometry.pixel(spec.x(), spec.y(), spec.z(), spec.facing().getModX(), spec.facing().getModZ(), spec.width(), spec.height(),
                new double[]{eye.getX(), eye.getY(), eye.getZ()}, new double[]{d.getX(), d.getY(), d.getZ()});
        if (pixel == null) pixel = new int[]{tx * MapTiles.SIZE + MapTiles.SIZE / 2, ty * MapTiles.SIZE + MapTiles.SIZE / 2};
        clicks++;
        try { wall.clicks.accept(new MapWall.Click(player, wall, tx, ty, pixel[0], pixel[1], attack)); }
        catch (RuntimeException failure) { ProviderFailures.report("ClientDisplays", wall.owner.getName(), "click", failure); }
    }

    private static double reach(Player player) {
        try {
            var range = player.getAttribute(Vystorm.vystorm_core.compat.ServerCompat.attribute("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE"));
            if (range != null) return range.getValue();
        } catch (RuntimeException | LinkageError ignored) { }
        return player.getGameMode() == org.bukkit.GameMode.CREATIVE ? 5 : 3;
    }

    private static void chunk(Player player, long key) {
        for (MapWall wall : WALLS) {
            MapWall.Viewer viewer = wall.viewers.get(player.getUniqueId());
            if (viewer == null) continue;
            // The client dropped (unload) or will get a fresh copy of (load) this chunk: spawn its frames again.
            for (int i : wall.tilesIn(key)) {
                if (viewer.shown[i]) { viewer.shown[i] = false; if (viewer.pending[i] != null) { viewer.pending[i] = null; viewer.pendingCount--; } }
                RECHECK.add(player.getUniqueId());
            }
        }
    }

    private static final class Events implements Listener {
        @EventHandler public void quit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            for (MapWall wall : WALLS) { wall.viewers.remove(id); wall.own.remove(id); }
            RECHECK.remove(id);
            LAST_CLICK.remove(id);
        }
        @EventHandler public void world(PlayerChangedWorldEvent e) { forget(e.getPlayer()); }
        @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { forget(e.getPlayer()); }
        @EventHandler public void loaded(Vystorm.vystorm_core.compat.ClientEvents.Loaded e) { chunk(e.getPlayer(), Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(e.getChunk())); }
        @EventHandler public void unloaded(Vystorm.vystorm_core.compat.ClientEvents.Unloaded e) { chunk(e.getPlayer(), Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(e.getChunk())); }
        @EventHandler public void use(Vystorm.vystorm_core.compat.ClientEvents.Interact e) {
            click(e.getPlayer(), e.getEntityId(), e.isAttack(), e.getHand());
        }
        @EventHandler public void disable(PluginDisableEvent e) {
            if (e.getPlugin() == core) {
                for (MapWall wall : List.copyOf(WALLS)) remove(wall);
                if (task != null) task.cancel();
                task = null;
                core = null;
                return;
            }
            release(e.getPlugin());
        }
    }
}
