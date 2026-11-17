package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.internal.ChunkPackets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Client-only chunks: whole chunks that exist only on one player's client, sent as ordinary chunk packets, for
 * previews of terrain the server has not generated yet. The server never has them: nothing is loaded, saved, ticked,
 * mined or built, and the server's own chunk sending is left alone.
 *
 * <ul>
 *   <li>{@link Draft}: describe the chunk (blocks, biomes per column), any thread.</li>
 *   <li>{@link #prepare}: build the packet with the server's own chunk serializer (any thread, after
 *       {@link #supported()} was checked once on the server thread). Sky light is computed from the blocks.</li>
 *   <li>{@link #send}: server thread. Only sent while the server has not sent the real chunk to that player; when the
 *       server sends the real chunk later, it simply replaces the client-only one on the client and the owner's
 *       {@link Replaced} listener runs.</li>
 *   <li>{@link #forget}: removes a client-only chunk from the client again (never touches a real chunk).</li>
 * </ul>
 *
 * The client only keeps and renders chunks within the view distance the server gives it, so client-only chunks
 * beyond {@code Vystorm.vystorm_core.compat.PlatformCompat.getSendViewDistance(player)} are not shown. One reflective adapter for Paper/Purpur 1.21.8 to 26.3,
 * probed once by building and encoding a test chunk ({@link #supported()}, {@link #unsupportedReason()}).
 * Server thread unless noted. @since 0.45.0
 * <p>Preferred access: {@code api.packages().clientChunks()}.
 */
public final class ClientChunks {
    /** called on the server thread when the server sent the real chunk over a client-only one */
    @FunctionalInterface
    public interface Replaced { void replaced(Player viewer, int chunkX, int chunkZ); }

    /** A client-only chunk ready to send (immutable; may be sent to several players of its world). */
    public static final class Prepared {
        private final UUID world;
        private final int chunkX, chunkZ, bytes, compressedBytes;
        private final long buildNanos;
        private final Object packet;

        Prepared(UUID world, int chunkX, int chunkZ, Object packet, int bytes, int compressedBytes, long buildNanos) {
            this.world = world; this.chunkX = chunkX; this.chunkZ = chunkZ; this.packet = packet;
            this.bytes = bytes; this.compressedBytes = compressedBytes; this.buildNanos = buildNanos;
        }
        public UUID world() { return world; }
        public int chunkX() { return chunkX; }
        public int chunkZ() { return chunkZ; }
        /** packet payload as the server's codec writes it, before network compression */
        public int bytes() { return bytes; }
        /** the payload deflated like the network layer does: an estimate of the bytes on the wire */
        public int compressedBytes() { return compressedBytes; }
        public long buildNanos() { return buildNanos; }
    }

    /**
     * The blocks and biomes of one client-only chunk. Not thread safe, but may be filled on any thread. Coordinates:
     * x and z 0-15 inside the chunk, y in world coordinates. Unset blocks are air, unset biomes plains.
     */
    public static final class Draft {
        final World world;
        final int chunkX, chunkZ, minY, sections;
        final short[] blocks;
        final List<BlockData> palette = new ArrayList<>();
        final Map<BlockData, Short> ids = new HashMap<>();
        final String[] biomes = new String[256];

        public Draft(World world, int chunkX, int chunkZ) {
            this.world = Objects.requireNonNull(world, "world");
            this.chunkX = chunkX; this.chunkZ = chunkZ;
            this.minY = world.getMinHeight();
            this.sections = (world.getMaxHeight() - minY) >> 4;
            this.blocks = new short[sections * 4096];
            palette.add(null);
        }

        public World world() { return world; }
        public int chunkX() { return chunkX; }
        public int chunkZ() { return chunkZ; }

        private short id(BlockData data) {
            Objects.requireNonNull(data, "data");
            if (air(data.getMaterial())) return 0;
            Short s = ids.get(data);
            if (s != null) return s;
            if (palette.size() >= Short.MAX_VALUE) throw new IllegalStateException("too many block types in one chunk");
            short n = (short) palette.size();
            palette.add(data);
            ids.put(data, n);
            return n;
        }

        /** one block; ignored outside the world height */
        public Draft set(int x, int y, int z, BlockData data) {
            short id = id(data);
            int ly = y - minY;
            if (ly < 0 || ly >= sections * 16) return this;
            blocks[(ly * 16 + (z & 15)) * 16 + (x & 15)] = id;
            return this;
        }

        /** blocks fromY (inclusive) to toY (exclusive) of one column, clipped to the world height */
        public Draft fill(int x, int z, int fromY, int toY, BlockData data) {
            short id = id(data);
            int a = Math.max(0, fromY - minY), b = Math.min(sections * 16, toY - minY);
            int c = (z & 15) * 16 + (x & 15);
            for (int y = a; y < b; y++) blocks[y * 256 + c] = id;
            return this;
        }

        /** biome of a column (applied to its whole height at the client's 4-block biome resolution) */
        public Draft biome(int x, int z, NamespacedKey biome) {
            biomes[(z & 15) * 16 + (x & 15)] = biome == null ? null : biome.toString();
            return this;
        }

        int[] opacity() {
            int[] o = new int[palette.size()];
            for (int i = 1; i < o.length; i++) o[i] = opacity(palette.get(i).getMaterial());
            return o;
        }

        static boolean air(Material m) { return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR; }

        static int opacity(Material m) {
            if (air(m)) return 0;
            return m.isOccluding() ? 15 : 1;
        }
    }

    /** Counters since start. Bytes as measured by {@link #prepare}. */
    public record Stats(long prepared, long prepareNanos, long sent, long sentBytes, long sentCompressedBytes, long forgotten,
                        long replaced, long refused, int shown, int viewers) {}

    private static final Map<UUID, Map<Long, Plugin>> SHOWN = new HashMap<>();
    private static final Map<Plugin, Replaced> LISTENERS = new IdentityHashMap<>();
    private static volatile ChunkPackets packets;
    private static String unsupported;
    private static boolean probed;
    private static Plugin core;
    private static BukkitTask task;
    private static final AtomicLong prepared = new AtomicLong(), prepareNanos = new AtomicLong();
    private static long sent, sentBytes, sentCompressed, forgotten, replaced, refused;

    private ClientChunks() {}

    /** True when this server can send client-only chunks (adapter built and a test chunk encodes). */
    public static boolean supported() { thread(); probe(); return packets != null; }

    /** Why {@link #supported()} is false; empty when supported. */
    public static Optional<String> unsupportedReason() { thread(); probe(); return Optional.ofNullable(unsupported); }

    /**
     * Builds the packet of a draft. Any thread, but {@link #supported()} must have returned true once (on the server
     * thread) before; empty otherwise or when building fails (reported once a minute in the debug log).
     */
    public static Optional<Prepared> prepare(Draft draft) {
        ChunkPackets p = packets;
        if (p == null) return Optional.empty();
        long t0 = System.nanoTime();
        try {
            String[] b = draft.biomes;
            ChunkPackets.Built built = p.build(draft.world, draft.chunkX, draft.chunkZ, draft.sections, draft.blocks, draft.palette,
                    draft.opacity(), (x, z) -> b[z * 16 + x]);
            long ns = System.nanoTime() - t0;
            prepared.incrementAndGet(); prepareNanos.addAndGet(ns);
            return Optional.of(new Prepared(draft.world.getUID(), draft.chunkX, draft.chunkZ, built.packet(), built.bytes(), built.compressed(), ns));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientChunks", "core", "prepare", failure);
            return Optional.empty();
        }
    }

    /**
     * Sends a client-only chunk to a player. Refused (false) when unsupported, the owner is disabled, the player is in
     * another world, the server already sent the real chunk to that player, or another plugin shows a client-only
     * chunk there.
     */
    public static boolean send(Plugin owner, Player viewer, Prepared chunk) {
        thread();
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(viewer, "viewer"); Objects.requireNonNull(chunk, "chunk");
        if (!supported() || !owner.isEnabled() || !viewer.isOnline() || !viewer.getWorld().getUID().equals(chunk.world)) { refused++; return false; }
        long key = key(chunk.chunkX, chunk.chunkZ);
        if (ClientBiomes.sent(viewer, key)) { refused++; return false; }
        Map<Long, Plugin> shown = SHOWN.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        Plugin other = shown.get(key);
        if (other != null && other != owner) { refused++; return false; }
        try { packets.send(viewer, chunk.packet); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientChunks", owner.getName(), "send", failure);
            refused++;
            return false;
        }
        start();
        shown.put(key, owner);
        sent++; sentBytes += chunk.bytes; sentCompressed += chunk.compressedBytes;
        return true;
    }

    /** Removes a client-only chunk of this owner from the player's client; false if it shows none there (or the real one). */
    public static boolean forget(Plugin owner, Player viewer, int chunkX, int chunkZ) {
        thread();
        Map<Long, Plugin> shown = SHOWN.get(viewer.getUniqueId());
        long key = key(chunkX, chunkZ);
        if (shown == null || shown.get(key) != owner) return false;
        shown.remove(key);
        if (ClientBiomes.sent(viewer, key) || packets == null || !viewer.isOnline()) return false;
        try { packets.send(viewer, packets.forget(chunkX, chunkZ)); forgotten++; return true; }
        catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientChunks", owner.getName(), "forget", failure);
            return false;
        }
    }

    /** Removes every client-only chunk of the owner from one player (all players with {@code viewer == null}). */
    public static void forgetAll(Plugin owner, Player viewer) {
        thread();
        List<Player> players = viewer != null ? List.of(viewer) : List.copyOf(Bukkit.getOnlinePlayers());
        for (Player p : players) for (long key : shown(owner, p)) forget(owner, p, (int) key, (int) (key >> 32));
    }

    /** Chunk keys ({@code x & 0xFFFFFFFF | z << 32}, like {@code Chunk.getChunkKey}) the owner shows to the player. */
    public static Set<Long> shown(Plugin owner, Player viewer) {
        thread();
        Map<Long, Plugin> shown = SHOWN.get(viewer.getUniqueId());
        if (shown == null) return Set.of();
        Set<Long> out = new java.util.HashSet<>();
        for (Map.Entry<Long, Plugin> e : shown.entrySet()) if (e.getValue() == owner) out.add(e.getKey());
        return out;
    }

    /** Whether any plugin shows a client-only chunk to the player at that position (as far as Core knows). */
    public static boolean showing(Player viewer, int chunkX, int chunkZ) {
        thread();
        Map<Long, Plugin> shown = SHOWN.get(viewer.getUniqueId());
        return shown != null && shown.containsKey(key(chunkX, chunkZ));
    }

    /** Listener for real chunks replacing the owner's client-only ones (one per plugin; null removes it). */
    public static void onReplaced(Plugin owner, Replaced listener) {
        thread();
        if (listener == null) LISTENERS.remove(owner); else LISTENERS.put(owner, listener);
    }

    /** Forgets every client-only chunk of the plugin and its listener (also done when it disables). */
    public static void release(Plugin owner) {
        thread();
        forgetAll(owner, null);
        LISTENERS.remove(owner);
        for (Map<Long, Plugin> m : SHOWN.values()) m.values().removeIf(p -> p == owner);
    }

    public static Stats stats() {
        thread();
        int shown = 0;
        for (Map<Long, Plugin> m : SHOWN.values()) shown += m.size();
        return new Stats(prepared.get(), prepareNanos.get(), sent, sentBytes, sentCompressed, forgotten, replaced, refused, shown, SHOWN.size());
    }

    /** Core lifecycle wiring. */
    public static void initialize(Plugin corePlugin) {
        thread();
        if (core != null) return;
        core = corePlugin;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void quit(PlayerQuitEvent e) { SHOWN.remove(e.getPlayer().getUniqueId()); }
            @EventHandler public void world(PlayerChangedWorldEvent e) { SHOWN.remove(e.getPlayer().getUniqueId()); }
            @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { SHOWN.remove(e.getPlayer().getUniqueId()); }
            @EventHandler public void disable(PluginDisableEvent e) { if (e.getPlugin() != core) dropOwner(e.getPlugin()); }
            @EventHandler(priority = EventPriority.MONITOR) public void loaded(Vystorm.vystorm_core.compat.ClientEvents.Loaded e) {
                Map<Long, Plugin> shown = SHOWN.get(e.getPlayer().getUniqueId());
                if (shown == null) return;
                Plugin owner = shown.remove(Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(e.getChunk()));
                if (owner == null) return;
                replaced++;
                Replaced l = LISTENERS.get(owner);
                if (l == null || !owner.isEnabled()) return;
                try { l.replaced(e.getPlayer(), e.getChunk().getX(), e.getChunk().getZ()); }
                catch (RuntimeException failure) { ProviderFailures.report("ClientChunks", owner.getName(), "replaced", failure); }
            }
        }, corePlugin);
    }

    public static void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        SHOWN.clear(); LISTENERS.clear();
    }

    private static void dropOwner(Plugin owner) {
        LISTENERS.remove(owner);
        for (Map<Long, Plugin> m : SHOWN.values()) m.values().removeIf(p -> p == owner);
    }

    /** the client drops chunks beyond its storage range (server view distance + 3) by itself: stop tracking them */
    private static void prune() {
        for (Iterator<Map.Entry<UUID, Map<Long, Plugin>>> it = SHOWN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Map<Long, Plugin>> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
            int range = clientRange(Vystorm.vystorm_core.compat.PlatformCompat.getSendViewDistance(p));
            e.getValue().keySet().removeIf(k -> Math.max(Math.abs((int) (long) k - cx), Math.abs((int) (k >> 32) - cz)) > range);
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    /** chunk radius the vanilla client keeps around its view centre for a server view distance */
    public static int clientRange(int viewDistance) { return Math.max(2, viewDistance) + 3; }

    private static void start() {
        if (task != null || core == null) return;
        task = Bukkit.getScheduler().runTaskTimer(core, ClientChunks::prune, 20, 20);
    }

    private static void probe() {
        if (probed) return;
        probed = true;
        if (Bukkit.getWorlds().isEmpty()) { probed = false; return; }
        try {
            ChunkPackets candidate = new ChunkPackets();
            candidate.probe(Bukkit.getWorlds().getFirst());
            packets = candidate;
            unsupported = null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unsupported = "client-only chunks need Paper/Purpur 1.21.8 to 26.3 (" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")";
            ProviderFailures.report("ClientChunks", "core", "probe", failure);
        }
    }

    static long key(int x, int z) { return ((long) x & 0xFFFFFFFFL) | ((long) z << 32); }

    private static void thread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ClientChunks requires the server thread"); }
}
