package Vystorm.vystorm_core.visual;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Shared visual primitives. No portal rules or ProtocolLib dependency. Bukkit-facing operations are
 * server-thread only; disabled owners are rejected and cleaned by CoreLifecycle. @since 0.29.0
 * <p>Preferred access: {@code api.particle().visuals()}.
 */
public final class Visuals {
    private static final Map<UUID, Session> VIEWS = new HashMap<>();
    private Visuals() {}
    public static BlockView open(Plugin owner, Player viewer) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(viewer);
        if (!owner.isEnabled() || !viewer.isOnline()) throw new IllegalStateException("Inactive owner or viewer");
        if (VIEWS.containsKey(viewer.getUniqueId())) throw new IllegalStateException("Viewer already owns a block view");
        Session session = new Session(owner, viewer);
        VIEWS.put(viewer.getUniqueId(), session);
        return session;
    }
    public static void release(Plugin owner) {
        if (owner == null || VIEWS.isEmpty()) return;
        thread();
        for (Session view : List.copyOf(VIEWS.values())) if (view.owner == owner) view.close();
    }
    public static void shutdown() {
        if (VIEWS.isEmpty()) return;
        thread();
        for (Session view : List.copyOf(VIEWS.values())) view.close();
    }
    public static Listener lifecycleListener() {
        return new Listener() {
            @EventHandler public void chunkLoaded(Vystorm.vystorm_core.compat.ClientEvents.Loaded e) {
                chunkUpdated(e.getPlayer(), e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
            }
            @EventHandler public void chunkUnloaded(Vystorm.vystorm_core.compat.ClientEvents.Unloaded e) {
                chunkUpdated(e.getPlayer(), e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
            }
            @EventHandler public void quit(PlayerQuitEvent e) { forget(e.getPlayer()); }
            @EventHandler public void changedWorld(PlayerChangedWorldEvent e) { forget(e.getPlayer()); }
            @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { forget(e.getPlayer()); }
        };
    }
    // A real chunk packet replaces client-side illusions. Forget that sent-state so the next
    // budgeted refresh re-applies it, including after dimension changes/initial chunk delivery.
    static void chunkUpdated(Player viewer, UUID world, int chunkX, int chunkZ) {
        thread();
        Session session = VIEWS.get(viewer.getUniqueId());
        if (session != null && session.world.equals(world))
            session.sent.keySet().removeIf(p -> (p.x() >> 4) == chunkX && (p.z() >> 4) == chunkZ);
    }
    private static void forget(Player viewer) {
        Session session = VIEWS.remove(viewer.getUniqueId());
        if (session != null) { session.closed = true; session.sent.clear(); }
    }
    /** Emit points in the viewer's current world. Maximum 512 particles per call; no global broadcast. */
    public static <T> void particles(Player viewer, Particle particle, List<Point3> points, T data) {
        thread(); Objects.requireNonNull(viewer); Objects.requireNonNull(particle); Objects.requireNonNull(points);
        if (points.size() > 512) throw new IllegalArgumentException("Particle point limit exceeded");
        if (!viewer.isOnline()) return;
        for (Point3 point : points) {
            Objects.requireNonNull(point);
            viewer.spawnParticle(particle, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0, data);
        }
    }
    /**
     * Sample destination blocks projected behind an upright aperture, clipped to the viewer's sight
     * through it. Radius 1..12, depth 1..16, maxBlocks 1..4096. Reads only loaded chunks in both worlds.
     * Tile entity payloads, light, collision and entities are not projected. Cardinal block orientations
     * are rotated to match the source frame. Caller owns refresh scheduling and distance limits.
     */
    public static Map<BlockPosition, BlockData> projection(ViewFrame entrance, ViewFrame destination,
            Point3 eye, int radius, int depth, int maxBlocks) {
        thread();
        if (radius < 1 || radius > 12 || depth < 1 || depth > 16 || maxBlocks < 1 || maxBlocks > 4096)
            throw new IllegalArgumentException("Projection limits out of bounds");
        World target = Bukkit.getWorld(entrance.world()), source = Bukkit.getWorld(destination.world());
        if (target == null || source == null || entrance.local(eye).z() <= 0) return Map.of();
        Map<BlockPosition, BlockData> blocks = new LinkedHashMap<>();
        int turns = Math.floorMod(entrance.quarterTurns() - destination.quarterTurns() + 2, 4);
        org.bukkit.block.structure.StructureRotation rotation = switch (turns) {
            case 1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90;
            case 2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180;
            case 3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90;
            default -> org.bukkit.block.structure.StructureRotation.NONE;
        };
        for (int z = 0; z < depth; z++) for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
            BlockPosition position = BlockPosition.containing(entrance.global(new Point3(x, y + .5, -z - .5)));
            if (!entrance.visible(eye, position.centre()) || !loaded(target, position)) continue;
            BlockPosition sample = BlockPosition.containing(entrance.through(destination, position.centre()));
            if (!loaded(source, sample)) continue;
            BlockData data = source.getBlockAt(sample.x(), sample.y(), sample.z()).getBlockData().clone();
            data.rotate(rotation);
            blocks.put(position, data);
            if (blocks.size() == maxBlocks) return Collections.unmodifiableMap(blocks);
        }
        return Collections.unmodifiableMap(blocks);
    }
    /**
     * Project loaded destination blocks beneath an upward-facing aperture. The opening mask contains
     * source-world block cells in the plane layer; gaps remain occluded (for example a centre pillar).
     * Radius 1..12, depth 1..16, maxBlocks 1..4096, mask at most 1024 cells. Server thread only.
     * Sampling only: does not load/generate chunks or change world blocks. @since 0.30.0
     */
    public static Map<BlockPosition, BlockData> projection(HorizontalFrame entrance, HorizontalFrame destination,
            Point3 eye, int radius, int depth, int maxBlocks, Set<BlockPosition> opening) {
        thread(); Objects.requireNonNull(entrance); Objects.requireNonNull(destination);
        Objects.requireNonNull(eye); Objects.requireNonNull(opening);
        if (radius < 1 || radius > 12 || depth < 1 || depth > 16 || maxBlocks < 1 || maxBlocks > 4096 || opening.size() > 1024)
            throw new IllegalArgumentException("Projection limits out of bounds");
        Set<BlockPosition> mask = Set.copyOf(opening);
        World target = Bukkit.getWorld(entrance.world()), source = Bukkit.getWorld(destination.world());
        if (mask.isEmpty() || target == null || source == null || eye.y() <= entrance.origin().y()) return Map.of();
        int centreX = (int) Math.floor(entrance.origin().x()), centreZ = (int) Math.floor(entrance.origin().z());
        int planeY = (int) Math.floor(entrance.origin().y());
        for (BlockPosition cell : mask) if (cell.y() != planeY)
            throw new IllegalArgumentException("Opening cells must be in the aperture plane layer");
        Map<BlockPosition, BlockData> blocks = new LinkedHashMap<>();
        for (int down = 0; down < depth; down++) for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
            BlockPosition position = new BlockPosition(centreX + x, planeY - down, centreZ + z);
            // Never remove neighbouring frame/floor cells in the aperture's own layer.
            if (down == 0 && !mask.contains(position)) continue;
            Optional<Point3> intersection = entrance.intersection(eye, position.centre());
            if (intersection.isEmpty() || !mask.contains(BlockPosition.containing(intersection.get())) || !loaded(target, position)) continue;
            BlockPosition sample = BlockPosition.containing(entrance.through(destination, position.centre()));
            if (!loaded(source, sample)) continue;
            blocks.put(position, source.getBlockAt(sample.x(), sample.y(), sample.z()).getBlockData().clone());
            if (blocks.size() == maxBlocks) return Collections.unmodifiableMap(blocks);
        }
        return Collections.unmodifiableMap(blocks);
    }
    private static boolean loaded(World world, BlockPosition p) {
        return p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight() && world.isChunkLoaded(p.x() >> 4, p.z() >> 4);
    }
    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Visuals requires the server thread");
    }
    private static final class Session implements BlockView {
        private final Plugin owner;
        private final Player viewer;
        private final UUID world;
        private final Map<BlockPosition, BlockData> sent = new LinkedHashMap<>();
        private boolean closed;
        Session(Plugin owner, Player viewer) { this.owner = owner; this.viewer = viewer; world = viewer.getWorld().getUID(); }
        @Override public boolean isClosed() { thread(); return closed; }
        @Override public boolean covers(BlockPosition position) { thread(); return !closed && sent.containsKey(position); }
        @Override public int replace(Map<BlockPosition, BlockData> desired, int budget) {
            return replace(desired, budget, false);
        }
        @Override public int replacePrioritized(Map<BlockPosition, BlockData> desired, int budget) {
            return replace(desired, budget, true);
        }
        private int replace(Map<BlockPosition, BlockData> desired, int budget, boolean prioritize) {
            thread(); Objects.requireNonNull(desired);
            if (closed) throw new IllegalStateException("View is closed");
            if (budget < 1 || budget > 4096 || desired.size() > 4096) throw new IllegalArgumentException("View budget exceeded");
            if (!owner.isEnabled() || !viewer.isOnline() || !viewer.getWorld().getUID().equals(world)) { close(); return 0; }
            World current = viewer.getWorld();
            Map<BlockPosition, BlockData> target = new LinkedHashMap<>();
            desired.forEach((p, d) -> { Objects.requireNonNull(p); Objects.requireNonNull(d); if (loaded(current, p)) target.put(p, d.clone()); });
            int remaining = 0;
            int cleanupBudget = prioritize ? Math.max(1, budget / 4) : budget;
            for (BlockPosition p : List.copyOf(sent.keySet())) {
                if (target.containsKey(p)) continue;
                if (cleanupBudget == 0) continue;
                if (loaded(current, p)) send(p, current.getBlockAt(p.x(), p.y(), p.z()).getBlockData());
                sent.remove(p); budget--; cleanupBudget--;
            }
            for (var entry : target.entrySet()) {
                if (entry.getValue().equals(sent.get(entry.getKey()))) continue;
                if (budget == 0) { remaining++; continue; }
                send(entry.getKey(), entry.getValue()); sent.put(entry.getKey(), entry.getValue()); budget--;
            }
            // Use any unused visible budget for cleanup, and count each pending restoration once.
            for (BlockPosition p : List.copyOf(sent.keySet())) {
                if (target.containsKey(p)) continue;
                if (budget == 0) { remaining++; continue; }
                if (loaded(current, p)) send(p, current.getBlockAt(p.x(), p.y(), p.z()).getBlockData());
                sent.remove(p); budget--;
            }
            return remaining;
        }
        private void send(BlockPosition p, BlockData data) {
            viewer.sendBlockChange(new Location(viewer.getWorld(), p.x(), p.y(), p.z()), data);
        }
        @Override public void close() {
            thread(); if (closed) return;
            closed = true; VIEWS.remove(viewer.getUniqueId(), this);
            if (viewer.isOnline() && viewer.getWorld().getUID().equals(world)) {
                World current = viewer.getWorld();
                for (BlockPosition p : sent.keySet()) if (loaded(current, p)) {
                    try { send(p, current.getBlockAt(p.x(), p.y(), p.z()).getBlockData()); }
                    catch (RuntimeException failure) { ProviderFailures.report("Visuals", owner.getName(), "restore", failure); }
                }
            }
            sent.clear();
        }
    }
}
