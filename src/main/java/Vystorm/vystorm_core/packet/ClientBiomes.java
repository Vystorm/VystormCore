package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.internal.BiomePackets;
import java.util.*;
import Vystorm.vystorm_core.visual.BlockPosition;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owner-bound client-only snowy biome views. All methods require the server thread.
 * Uses vanilla snowy_plains (including its colours); never edits saved biomes or blocks.
 * Does not enable precipitation. Only Paper/Purpur 26.2 and 26.3 are supported. @since 0.33.0
 * <p>Preferred access: {@code api.packages().clientBiomes()}.
 */
public final class ClientBiomes {
    /**
     * Minecraft versions whose biome packet and palette wire format the adapter was checked against. 26.3 keeps the
     * 26.2 format (ChunkBiomeData constructors, ChunkPos + byte array codec, unchanged PalettedContainer/palettes);
     * only the packet ids moved, and those are resolved by the server.
     */
    static final Set<String> VERIFIED_VERSIONS = Set.of("26.2", "26.3");
    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static BukkitTask task;
    private static int cursor;
    private ClientBiomes() {}
    public static boolean supported() { return VERIFIED_VERSIONS.contains(Vystorm.vystorm_core.compat.PlatformCompat.getMinecraftVersion()) && BiomePackets.available(); }
    /** Start a view, or retain the identical existing view. One owner per player. Returns false on unsupported runtime. */
    public static boolean snow(Plugin owner, Player viewer, Set<String> excludedBiomes) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(viewer);
        Set<String> excluded = Set.copyOf(excludedBiomes);
        if (!supported() || !owner.isEnabled() || !viewer.isOnline()) return false;
        View old = VIEWS.get(viewer.getUniqueId());
        if (old != null && old.owner != owner) return false;
        if (old != null && old.world.equals(viewer.getWorld().getUID()) && old.excluded.equals(excluded)) return true;
        clear(owner, viewer);
        try { VIEWS.put(viewer.getUniqueId(), new View(owner, viewer, excluded)); return true; }
        catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientBiomes", owner.getName(), "open", failure); return false;
        }
    }
    /** Replace a spatial overlay (<=4096 four-block-aligned quart cells, <=16 chunks).
     * Composes over snow views, takes destination biome keys, never edits server biomes.
     * Vanilla quart resolution/blending can tint adjacent blocks. @since 0.36.0 */
    public static boolean project(Plugin owner, Player viewer, Map<BlockPosition, NamespacedKey> cells) {
        thread(); return ProjectedBiomes.replace(owner, viewer, cells);
    }
    /** Restore the snowy base view if present, otherwise real biomes. @since 0.36.0 */
    public static void clearProjection(Plugin owner, Player viewer) {
        thread(); ProjectedBiomes.clear(owner, viewer);
    }
    static void sendBase(Player viewer, Collection<Chunk> chunks, BiomePackets fallback,
            Map<BlockPosition, NamespacedKey> cells) throws ReflectiveOperationException {
        View base = VIEWS.get(viewer.getUniqueId());
        boolean snow = base != null && base.world.equals(viewer.getWorld().getUID());
        (snow ? base.adapter : fallback).send(viewer, chunks, snow, cells);
    }
    /** Restore current real biome data to the viewer and release the view. */
    public static void clear(Plugin owner, Player viewer) {
        thread(); View view = VIEWS.get(viewer.getUniqueId());
        if (view != null && view.owner == owner) { VIEWS.remove(viewer.getUniqueId()); view.restore(); }
    }
    public static void release(Plugin owner) {
        thread(); ProjectedBiomes.release(owner); for (View view : List.copyOf(VIEWS.values())) if (view.owner == owner) clear(owner, view.player);
    }
    public static void shutdown() {
        thread(); ProjectedBiomes.shutdown(); for (View view : List.copyOf(VIEWS.values())) clear(view.owner, view.player);
        if (task != null) { task.cancel(); task = null; }
    }
    /** Core lifecycle wiring; starts one shared budgeted sender. */
    public static void initialize(Plugin core) {
        thread(); if (task != null) return;
        if (!supported()) core.getLogger().info("Client-only biome effects unavailable: the packet codec is verified only for 26.2/26.3.");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void quit(PlayerQuitEvent e) { VIEWS.remove(e.getPlayer().getUniqueId()); ProjectedBiomes.forget(e.getPlayer()); }
            @EventHandler public void world(PlayerChangedWorldEvent e) { VIEWS.remove(e.getPlayer().getUniqueId()); ProjectedBiomes.forget(e.getPlayer()); }
            @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { VIEWS.remove(e.getPlayer().getUniqueId()); ProjectedBiomes.forget(e.getPlayer()); }
            @EventHandler public void loaded(Vystorm.vystorm_core.compat.ClientEvents.Loaded e) {
                ProjectedBiomes.loaded(e.getPlayer(), e.getWorld().getUID(), Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(e.getChunk()));
                View view = VIEWS.get(e.getPlayer().getUniqueId());
                if (view != null && view.world.equals(e.getWorld().getUID())) view.pending.add(Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(e.getChunk()));
            }
        }, core);
        task = Bukkit.getScheduler().runTaskTimer(core, ClientBiomes::tick, 1, 1);
    }
    private static void tick() {
        ProjectedBiomes.tick();
        int budget = 64;
        List<View> current = new ArrayList<>(VIEWS.values());
        if (!current.isEmpty()) { Collections.rotate(current, -(cursor % current.size())); cursor = (cursor + 8) % current.size(); }
        for (View view : current) {
            if (!view.owner.isEnabled()) { clear(view.owner, view.player); continue; }
            if (!view.player.isOnline() || !view.world.equals(view.player.getWorld().getUID())) { VIEWS.remove(view.player.getUniqueId()); continue; }
            // A bounded periodic refresh also repairs biome updates from other providers.
            // A bounded periodic refresh also repairs biome updates from other providers. While a player changes world
            // or leaves, Paper has no chunk loader for them and the sent-chunk queries throw: skip them this tick.
            if (++view.ticks % 200 == 0) view.pending.addAll(sentKeys(view.player));
            if (budget <= 0) continue;
            List<Chunk> batch = new ArrayList<>();
            Iterator<Long> it = view.pending.iterator();
            while (it.hasNext() && batch.size() < Math.min(8, budget)) {
                long key = it.next(); it.remove();
                int x = (int) key, z = (int)(key >> 32);
                if (sent(view.player, key) && view.player.getWorld().isChunkLoaded(x,z)) batch.add(view.player.getWorld().getChunkAt(x,z));
            }
            if (!batch.isEmpty()) {
                try { sendComposed(view.adapter, view.player, batch, true); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    ProviderFailures.report("ClientBiomes", view.owner.getName(), "send", failure);
                    clear(view.owner, view.player);
                }
                budget -= batch.size();
            }
        }
    }
    private static void sendComposed(BiomePackets adapter, Player player, Collection<Chunk> chunks, boolean snowy)
            throws ReflectiveOperationException {
        var cells = ProjectedBiomes.cells(player);
        if (cells.isEmpty()) adapter.send(player, chunks, snowy);
        else adapter.send(player, chunks, snowy, cells);
    }
    private static void thread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ClientBiomes requires the server thread"); }
    /** Sent chunks, or none while the player is between worlds or leaving (Paper throws then). */
    static Set<Long> sentKeys(Player player) {
        try { return Vystorm.vystorm_core.compat.ClientEvents.sentKeys(player); } catch (RuntimeException inTransition) { return Set.of(); }
    }
    static boolean sent(Player player, long key) {
        try { return Vystorm.vystorm_core.compat.ClientEvents.sent(player,key); } catch (RuntimeException inTransition) { return false; }
    }
    private static final class View {
        final Plugin owner; final Player player; final UUID world; final Set<String> excluded;
        final BiomePackets adapter; final Set<Long> pending = new LinkedHashSet<>(); int ticks;
        View(Plugin owner, Player player, Set<String> excluded) throws ReflectiveOperationException {
            this.owner=owner; this.player=player; this.excluded=excluded; this.world=player.getWorld().getUID();
            adapter=new BiomePackets(player.getWorld(), excluded); pending.addAll(sentKeys(player));
        }
        void restore() {
            if (!player.isOnline() || !world.equals(player.getWorld().getUID())) return;
            List<Chunk> batch = new ArrayList<>(32);
            try {
                for (Chunk chunk : Vystorm.vystorm_core.compat.ClientEvents.sentChunks(player)) {
                    batch.add(chunk);
                    if (batch.size() == 32) { sendComposed(adapter,player,batch,false); batch.clear(); }
                }
                sendComposed(adapter,player,batch,false);
            } catch (ReflectiveOperationException | RuntimeException failure) { ProviderFailures.report("ClientBiomes", owner.getName(), "restore", failure); }
            pending.clear();
        }
    }
}
