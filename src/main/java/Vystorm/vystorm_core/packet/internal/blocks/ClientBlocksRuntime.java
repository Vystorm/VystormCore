package Vystorm.vystorm_core.packet.internal.blocks;

import Vystorm.vystorm_core.compat.ClientEvents;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.ClientBlockInteract;
import Vystorm.vystorm_core.packet.ClientBlockSet;
import Vystorm.vystorm_core.packet.ClientBlocksStats;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

/**
 * Server side of client blocks: turns {@code ClientBlockSet} calls into {@link ClientBlockStore} changes, runs the
 * store once per tick, sends its batches (one {@code PlatformCompat.sendMultiBlockChange} per section, a single block
 * change for one cell), keeps shared sets attached to the players in range, and feeds block events in through
 * {@link ClientBlocksListener}.
 *
 * <p><b>Threads:</b> every method runs on the server thread, except {@link #stats()} (a snapshot, any thread).
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ClientBlocksRuntime implements ClientBlockStore.Env {
    private static ClientBlocksRuntime instance;

    final ClientBlockStore store = new ClientBlockStore();
    final StateIds states = new StateIds();
    private Plugin core;
    private BukkitTask task;
    private ClientBlocksListener listener;
    private long ticks;
    private boolean physics;
    private final Set<String> collisionWarned = new HashSet<>();
    private final List<Handle> sharedSets = new ArrayList<>();
    private volatile ClientBlocksStats snapshot = ClientBlocksStats.empty();
    private long sendFailures;

    /** The runtime; created on first use (unit tests use it without {@link #start}). */
    public static synchronized ClientBlocksRuntime get() {
        if (instance == null) instance = new ClientBlocksRuntime();
        return instance;
    }

    /** Core lifecycle and tests: restores what is still shown and forgets the runtime. */
    public static synchronized void shutdownInstance() {
        if (instance == null) return;
        ClientBlocksRuntime old = instance;
        instance = null;
        old.stop();
    }

    /** Starts the tick task and the listeners; budgets from Core's config. */
    public void start(Plugin core) {
        this.core = Objects.requireNonNull(core);
        store.budgets(core.getConfig().getInt("client-blocks.cells-per-tick", ClientBlockStore.DEFAULT_VIEWER_BUDGET),
                core.getConfig().getInt("client-blocks.cells-per-tick-total", ClientBlockStore.DEFAULT_TOTAL_BUDGET));
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(core, this::tick, 1L, 1L);
        if (listener != null) listener.unregister();
        listener = new ClientBlocksListener(this);
        listener.register(core);
    }

    private void stop() {
        if (Bukkit.isPrimaryThread()) {
            for (ViewerModel v : store.viewers()) {
                for (BlockSetModel set : v.sets) markAllUrgent(set, v);
            }
            for (BlockSetModel set : store.openSets()) store.close(set);
            for (ViewerModel v : store.viewers()) {
                // Restores still waiting for budget (sets closed by plugin disables just before, as on a reload)
                // must go out now: the tick task stops with Core.
                store.urgentAll(v);
                try { store.flushUrgent(v.id, this); }
                catch (RuntimeException failure) { log().log(Level.FINE, "Client block restore failed", failure); }
            }
        }
        if (task != null) task.cancel();
        task = null;
        if (listener != null) listener.unregister();
        listener = null;
        sharedSets.clear();
    }

    // ------------------------------------------------------------------ public operations

    public ClientBlockSet open(Plugin owner, Player viewer, String name, int priority) {
        thread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewer, "viewer");
        name = checkName(name);
        if (!owner.isEnabled() || !viewer.isOnline()) throw new IllegalStateException("Inactive owner or viewer");
        ViewerModel v = viewer(viewer);
        BlockSetModel model = store.open(owner.getName(), name, priority, false);
        if (!store.attach(model, v)) {
            store.close(model);
            throw new IllegalStateException("Viewer " + viewer.getName() + " is at the client block limit ("
                    + ClientBlockStore.MAX_SETS_PER_VIEWER + " sets, " + ClientBlockStore.MAX_VIEWER_CELLS + " cells)");
        }
        World world = viewer.getWorld();
        Handle handle = new Handle(this, model, owner, world.getMinHeight(), world.getMaxHeight(), null, 0, 0, 0, 0);
        model.handle = handle;
        return handle;
    }

    /** A block view of {@code Visuals}: a set of priority 0 named visuals that sends solid cells inside the viewer's box, as before 0.72.0. */
    public ClientBlockSet openView(Plugin owner, Player viewer) {
        Handle h = (Handle) open(owner, viewer, "visuals", 0);
        h.model.boxRule = false;
        return h;
    }

    /** Called with the player's id when Core forgets a viewer (quit, world change, respawn). */
    private static final List<Consumer<UUID>> FORGET_HOOKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void onForget(Consumer<UUID> hook) { FORGET_HOOKS.add(hook); }

    public ClientBlockSet openShared(Plugin owner, Location anchor, int range, String name, int priority) {
        thread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(anchor, "anchor");
        name = checkName(name);
        if (range < 1 || range > 256) throw new IllegalArgumentException("range must be 1..256");
        if (!owner.isEnabled()) throw new IllegalStateException("Inactive owner");
        World world = anchor.getWorld();
        if (world == null || Bukkit.getWorld(world.getUID()) == null) throw new IllegalStateException("Anchor world is not loaded");
        BlockSetModel model = store.open(owner.getName(), name, priority, true);
        Handle handle = new Handle(this, model, owner, world.getMinHeight(), world.getMaxHeight(), world.getUID(),
                anchor.getX(), anchor.getY(), anchor.getZ(), range);
        model.handle = handle;
        sharedSets.add(handle);
        membership(handle);
        return handle;
    }

    public Optional<BlockData> shown(Player viewer, int x, int y, int z) {
        thread();
        ViewerModel v = store.find(viewer.getUniqueId());
        if (v == null || !v.world.equals(viewer.getWorld().getUID())) return Optional.empty();
        int state = v.shown(x, y, z);
        return state < 0 ? Optional.empty() : Optional.of(states.data(state).clone());
    }

    public List<ClientBlockSet> sets(Player viewer) {
        thread();
        ViewerModel v = store.find(viewer.getUniqueId());
        if (v == null || !v.world.equals(viewer.getWorld().getUID())) return List.of();
        List<ClientBlockSet> out = new ArrayList<>(v.sets.size());
        for (BlockSetModel set : v.sets) if (set.handle instanceof ClientBlockSet h) out.add(h);
        return List.copyOf(out);
    }

    public void release(Plugin owner) {
        if (owner == null || store.openSetCount() == 0) return;
        thread();
        for (BlockSetModel set : store.openSets())
            if (set.handle instanceof Handle h && h.owner == owner) closeSet(h);
    }

    public ClientBlocksStats stats() {
        if (Bukkit.isPrimaryThread()) snapshot = computeStats();
        return snapshot;
    }

    // ------------------------------------------------------------------ for Visuals: sends in the same call

    /**
     * Applies changes of a set and sends exactly these cells now, without a budget (the block view's own call limits
     * them). Other pending cells of the viewer wait for the tick.
     */
    public void applyNow(ClientBlockSet set, Map<BlockPosition, BlockData> put, Collection<BlockPosition> remove) {
        thread();
        Handle h = (Handle) set;
        if (h.model.closed) return;
        int n = put.size() + remove.size();
        long[] keys = new long[n];
        int k = 0;
        if (!remove.isEmpty()) {
            long[] gone = new long[remove.size()];
            int g = 0;
            for (BlockPosition p : remove) if (h.inWorld(p)) gone[g++] = cell(p);
            store.removeAll(h.model, gone, g);
            System.arraycopy(gone, 0, keys, 0, g);
            k = g;
        }
        if (!put.isEmpty()) {
            long[] cells = new long[put.size()];
            int[] ids = new int[put.size()];
            int c = 0;
            for (Map.Entry<BlockPosition, BlockData> e : put.entrySet()) {
                if (!h.inWorld(e.getKey())) continue;
                cells[c] = cell(e.getKey());
                ids[c++] = states.id(e.getValue());
            }
            store.putAll(h.model, cells, ids, c);
            System.arraycopy(cells, 0, keys, k, c);
            k += c;
        }
        for (ViewerModel v : List.copyOf(h.model.viewers)) {
            store.markUrgent(v, keys, k);
            store.flushUrgent(v.id, this);
        }
    }

    /** Closes a set; with {@code restoreNow} its cells are restored in the same call, otherwise nothing is sent now. */
    public void closeNow(ClientBlockSet set, boolean restoreNow) {
        thread();
        Handle h = (Handle) set;
        if (h.model.closed) return;
        List<ViewerModel> viewers = List.copyOf(h.model.viewers);
        if (restoreNow) for (ViewerModel v : viewers) markAllUrgent(h.model, v);
        closeSet(h);
        if (restoreNow) for (ViewerModel v : viewers) store.flushUrgent(v.id, this);
    }

    /** A real chunk arrived at the viewer (also used by {@code Visuals.chunkUpdated}). */
    public void chunkLoaded(Player viewer, UUID world, int chunkX, int chunkZ) {
        ViewerModel v = store.find(viewer.getUniqueId());
        if (v != null && v.world.equals(world)) store.chunkLoaded(v.id, chunkX, chunkZ);
    }

    public void chunkUnloaded(Player viewer, UUID world, int chunkX, int chunkZ) {
        ViewerModel v = store.find(viewer.getUniqueId());
        if (v != null && v.world.equals(world)) store.chunkUnloaded(v.id, chunkX, chunkZ);
    }

    /** Quit, world change, respawn: the client dropped its world; per-player sets close without sending. */
    public void forget(Player viewer) {
        store.dropViewer(viewer.getUniqueId());
        for (Consumer<UUID> hook : FORGET_HOOKS) {
            try { hook.accept(viewer.getUniqueId()); }
            catch (RuntimeException failure) { log().log(Level.WARNING, "Client blocks: forget hook failed", failure); }
        }
    }

    // ------------------------------------------------------------------ tick

    /** One tick: shared set membership (every 10 ticks), the store's sending, the physics listener switch. */
    public void tick() {
        ticks++;
        if (!sharedSets.isEmpty() && ticks % 10 == 0) for (Handle h : List.copyOf(sharedSets)) membership(h);
        try {
            store.tick(ticks, this);
        } catch (RuntimeException failure) {
            log().log(Level.WARNING, "Client blocks: tick failed", failure);
        }
        boolean wantPhysics = store.openSetCount() > 0;
        if (listener != null && wantPhysics != physics) {
            physics = wantPhysics;
            listener.physics(physics);
        }
        if (ticks % 20 == 0) snapshot = computeStats();
    }

    private void membership(Handle h) {
        if (h.model.closed) {
            sharedSets.remove(h);
            return;
        }
        World world = Bukkit.getWorld(h.anchorWorld);
        Set<UUID> inRange = new HashSet<>();
        if (world != null) {
            double r2 = (double) h.range * h.range;
            for (Player p : world.getPlayers()) {
                Location l = p.getLocation();
                double dx = l.getX() - h.ax, dy = l.getY() - h.ay, dz = l.getZ() - h.az;
                if (dx * dx + dy * dy + dz * dz > r2 || !p.isOnline()) continue;
                ViewerModel v = viewer(p);
                if (store.attach(h.model, v)) inRange.add(p.getUniqueId());
            }
        }
        for (UUID id : h.model.viewerIds()) {
            if (inRange.contains(id)) continue;
            ViewerModel v = store.find(id);
            if (v != null) store.detach(h.model, v);
        }
    }

    // ------------------------------------------------------------------ events from the listener

    /** A real block changed (or may have) at a cell; {@code except} is not repaired (null = everyone). */
    void changed(World world, int x, int y, int z, UUID except) {
        store.realChanged(world.getUID(), x, y, z, except);
    }

    boolean watched(World world, int x, int y, int z) { return store.watched(world.getUID(), x, y, z); }

    boolean watchedChunk(World world, int chunkX, int chunkZ) {
        return store.watchedChunk(world.getUID(), chunkX, chunkZ, world.getMinHeight() >> 4, (world.getMaxHeight() - 1) >> 4);
    }

    /** The client of {@code player} was told the real block at a cell: send the client block again for him. */
    void repairFor(Player player, int x, int y, int z) { store.repair(player.getUniqueId(), x, y, z); }

    /**
     * A player clicked or broke a cell. Returns true when Core showed him a client block there (the listener of
     * its set, if any, was told).
     */
    boolean interact(Player player, Block block, ClientBlockInteract.Action action) {
        int x = block.getX(), y = block.getY(), z = block.getZ();
        ViewerModel v = store.find(player.getUniqueId());
        if (v == null || !v.world.equals(block.getWorld().getUID())) return false;
        BlockSetModel model = store.shownBy(player.getUniqueId(), x, y, z);
        if (model == null || !(model.handle instanceof Handle h)) return false;
        Consumer<ClientBlockInteract> callback = h.listener;
        if (callback == null) {
            store.repair(player.getUniqueId(), x, y, z);
            return true;
        }
        Interact event = new Interact(player, h, new BlockPosition(x, y, z), states.data(v.shown(x, y, z)), action);
        try {
            callback.accept(event);
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report("ClientBlocks", h.owner.getName(), "onInteract", failure);
            event.repair = true;
        }
        if (event.repair) store.repair(player.getUniqueId(), x, y, z);
        // A right click and a break make the server send the real block; a left click or the start of breaking does
        // not, so the client still shows the client block and Core keeps counting it (restores stay correct).
        else if (action == ClientBlockInteract.Action.RIGHT_CLICK || action == ClientBlockInteract.Action.BREAK)
            store.forgetShown(player.getUniqueId(), x, y, z);
        return true;
    }

    // ------------------------------------------------------------------ Env

    private static final class ViewerHandle {
        final Player player;
        World world;
        boolean tracked, trackedKnown;
        boolean box;
        double minX, minY, minZ, maxX, maxY, maxZ;

        ViewerHandle(Player player) { this.player = player; }
    }

    private ViewerModel viewer(Player p) {
        World w = p.getWorld();
        ViewerModel v = store.viewer(p.getUniqueId(), w.getUID(), w.getMinHeight() >> 4, (w.getMaxHeight() - 1) >> 4);
        if (!(v.handle instanceof ViewerHandle h) || h.player != p) v.handle = new ViewerHandle(p);
        return v;
    }

    @Override
    public boolean locate(ViewerModel v) {
        if (!(v.handle instanceof ViewerHandle h)) return false;
        Player p = h.player;
        if (!p.isOnline()) return false;
        World world = p.getWorld();
        if (world == null || !world.getUID().equals(v.world)) return false;
        h.world = world;
        Location eye = p.getEyeLocation();
        if (eye != null) {
            v.eyeX = eye.getX();
            v.eyeY = eye.getY();
            v.eyeZ = eye.getZ();
        }
        h.trackedKnown = false;
        h.box = false;
        try {
            BoundingBox box = p.getBoundingBox();
            if (box != null) {
                h.minX = box.getMinX(); h.minY = box.getMinY(); h.minZ = box.getMinZ();
                h.maxX = box.getMaxX(); h.maxY = box.getMaxY(); h.maxZ = box.getMaxZ();
                h.box = true;
            }
        } catch (RuntimeException | LinkageError noBox) { }
        return true;
    }

    @Override
    public boolean hasChunk(ViewerModel v, int chunkX, int chunkZ) {
        ViewerHandle h = (ViewerHandle) v.handle;
        try {
            if (ClientEvents.sent(h.player, PlatformCompat.getChunkKey(chunkX, chunkZ))) return true;
        } catch (RuntimeException inTransition) {
            return false;
        }
        // Client tracking unknown (no chunk known at all): fall back to the server's loaded chunks.
        if (!h.trackedKnown) {
            h.trackedKnown = true;
            try { h.tracked = !ClientEvents.sentKeys(h.player).isEmpty(); }
            catch (RuntimeException inTransition) { h.tracked = false; }
        }
        return !h.tracked && h.world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean inBox(ViewerModel v, int x, int y, int z) {
        ViewerHandle h = (ViewerHandle) v.handle;
        return h.box && x < h.maxX && x + 1 > h.minX && y < h.maxY && y + 1 > h.minY && z < h.maxZ && z + 1 > h.minZ;
    }

    @Override
    public boolean realSolid(ViewerModel v, int x, int y, int z) {
        ViewerHandle h = (ViewerHandle) v.handle;
        try {
            if (Bukkit.getServer() == null) return true; // unit tests: no registry for Material.isSolid
            return !h.world.isChunkLoaded(x >> 4, z >> 4) || h.world.getBlockAt(x, y, z).getType().isSolid();
        } catch (RuntimeException | LinkageError unknown) {
            return true;
        }
    }

    @Override
    public boolean solid(int state) { return states.solid(state); }

    @Override
    public void send(ViewerModel v, long section, short[] locals, int[] ids, int count) {
        ViewerHandle h = (ViewerHandle) v.handle;
        Player p = h.player;
        World world = h.world != null ? h.world : p.getWorld();
        try {
            if (count == 1) {
                int x = CellKeys.blockX(section, locals[0]), y = CellKeys.blockY(section, locals[0]), z = CellKeys.blockZ(section, locals[0]);
                BlockData data = data(world, x, y, z, ids[0]);
                if (data != null) p.sendBlockChange(new Location(world, x, y, z), data);
                return;
            }
            Map<Location, BlockData> cells = new LinkedHashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                int x = CellKeys.blockX(section, locals[i]), y = CellKeys.blockY(section, locals[i]), z = CellKeys.blockZ(section, locals[i]);
                BlockData data = data(world, x, y, z, ids[i]);
                if (data != null) cells.put(new Location(world, x, y, z), data);
            }
            if (cells.size() == 1) {
                var only = cells.entrySet().iterator().next();
                p.sendBlockChange(only.getKey(), only.getValue());
            } else if (!cells.isEmpty()) PlatformCompat.sendMultiBlockChange(p, cells);
        } catch (RuntimeException failure) {
            if (sendFailures++ % 1000 == 0) log().log(Level.WARNING, "Client blocks: sending to " + p.getName() + " failed", failure);
        }
    }

    private BlockData data(World world, int x, int y, int z, int id) {
        if (id != ClientBlockStore.REAL) return states.data(id);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        Block block = world.getBlockAt(x, y, z);
        return block == null ? null : block.getBlockData();
    }

    // ------------------------------------------------------------------ helpers

    private void markAllUrgent(BlockSetModel set, ViewerModel v) {
        long[] keys = new long[set.size];
        int n = 0;
        for (Map.Entry<Long, CellMap> e : set.sections.entrySet()) {
            long section = e.getKey();
            CellMap m = e.getValue();
            for (int slot = 0; slot < m.slots(); slot++) {
                int local = m.localAt(slot);
                if (local >= 0) keys[n++] = CellKeys.cell(CellKeys.blockX(section, local), CellKeys.blockY(section, local), CellKeys.blockZ(section, local));
            }
        }
        store.markUrgent(v, keys, n);
    }

    void closeSet(Handle h) {
        store.close(h.model);
        sharedSets.remove(h);
    }

    void declaredCollision(Handle h) {
        if (!collisionWarned.add(h.owner.getName())) return;
        log().warning("Client blocks: plugin " + h.owner.getName() + " shows client blocks that change what players can stand on or walk into (set '"
                + h.model.name + "'). Players may rubber-band there, and anti-cheat plugins may flag them. See /vadmin packets.");
    }

    private ClientBlocksStats computeStats() {
        Map<String, int[]> owners = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Set<UUID>> ownerViewers = new HashMap<>();
        int cells = 0;
        List<BlockSetModel> open = store.openSets();
        for (BlockSetModel set : open) {
            int[] row = owners.computeIfAbsent(set.owner, ignored -> new int[3]);
            row[0]++;
            row[1] += set.size;
            if (set.collision) row[2]++;
            cells += set.size;
            ownerViewers.computeIfAbsent(set.owner, ignored -> new HashSet<>()).addAll(set.viewerIds());
        }
        int viewers = 0;
        for (ViewerModel v : store.viewers()) if (!v.sets.isEmpty()) viewers++;
        List<ClientBlocksStats.Owner> rows = new ArrayList<>();
        owners.forEach((name, row) -> rows.add(new ClientBlocksStats.Owner(name, row[0], row[1],
                ownerViewers.getOrDefault(name, Set.of()).size(), row[2])));
        ClientBlockStore.Counters c = store.counters();
        return new ClientBlocksStats(open.size(), viewers, cells, c.cellsSent(), c.batches(), c.singleBatches(), c.restores(),
                c.repairs(), c.blocked(), c.refused(), rows);
    }

    /** Lines for {@code /vadmin packets}: open sets with owner, name, priority, size, viewers, pending, flags. */
    public List<String[]> setRows() {
        List<String[]> rows = new ArrayList<>();
        for (BlockSetModel set : store.openSets())
            rows.add(new String[]{set.owner, set.name, Integer.toString(set.priority), Integer.toString(set.size),
                    Integer.toString(set.viewers.size()), Integer.toString(store.pending(set)), set.shared ? "shared" : "player",
                    set.collision ? "collision" : "visual"});
        return rows;
    }

    public int viewerBudget() { return store.viewerBudget(); }
    public int totalBudget() { return store.totalBudget(); }
    public long ticks() { return ticks; }

    private Logger log() { return core != null ? core.getLogger() : Logger.getLogger("Vystorm_Core"); }

    static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Client blocks require the server thread");
    }

    private static String checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("name must be 1..64 characters");
        return name;
    }

    static long cell(BlockPosition p) { return CellKeys.cell(p.x(), p.y(), p.z()); }

    // ------------------------------------------------------------------ the public set handle

    static final class Handle implements ClientBlockSet {
        final ClientBlocksRuntime rt;
        final BlockSetModel model;
        final Plugin owner;
        final int minY, maxY;
        final UUID anchorWorld;
        final double ax, ay, az;
        final int range;
        Consumer<ClientBlockInteract> listener;

        Handle(ClientBlocksRuntime rt, BlockSetModel model, Plugin owner, int minY, int maxY, UUID anchorWorld,
               double ax, double ay, double az, int range) {
            this.rt = rt;
            this.model = model;
            this.owner = owner;
            this.minY = minY;
            this.maxY = maxY;
            this.anchorWorld = anchorWorld;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.range = range;
        }

        boolean inWorld(BlockPosition p) {
            Objects.requireNonNull(p, "cell");
            return p.y() >= minY && p.y() < maxY && Math.abs(p.x()) <= 30_000_000 && Math.abs(p.z()) <= 30_000_000;
        }

        @Override public Plugin owner() { return owner; }
        @Override public String name() { return model.name; }
        @Override public int priority() { return model.priority; }
        @Override public boolean shared() { return model.shared; }
        @Override public boolean changesCollision() { return model.collision; }

        @Override public ClientBlockSet set(BlockPosition cell, BlockData data) {
            Objects.requireNonNull(data, "data");
            return setAll(Map.of(cell, data));
        }

        @Override public ClientBlockSet setAll(Map<BlockPosition, BlockData> cells) {
            thread();
            Objects.requireNonNull(cells, "cells");
            if (model.closed) return this;
            long[] keys = new long[cells.size()];
            int[] ids = new int[cells.size()];
            int n = 0;
            for (Map.Entry<BlockPosition, BlockData> e : cells.entrySet()) {
                BlockData data = Objects.requireNonNull(e.getValue(), "data");
                if (!inWorld(e.getKey())) continue;
                keys[n] = cell(e.getKey());
                ids[n++] = rt.states.id(data);
            }
            rt.store.putAll(model, keys, ids, n);
            return this;
        }

        @Override public ClientBlockSet replace(Map<BlockPosition, BlockData> desired) {
            thread();
            Objects.requireNonNull(desired, "desired");
            if (model.closed) return this;
            long[] keys = new long[desired.size()];
            int[] ids = new int[desired.size()];
            int n = 0;
            for (Map.Entry<BlockPosition, BlockData> e : desired.entrySet()) {
                BlockData data = Objects.requireNonNull(e.getValue(), "data");
                if (!inWorld(e.getKey())) continue;
                keys[n] = cell(e.getKey());
                ids[n++] = rt.states.id(data);
            }
            rt.store.replace(model, keys, ids, n);
            return this;
        }

        @Override public ClientBlockSet clear(Collection<BlockPosition> cells) {
            thread();
            Objects.requireNonNull(cells, "cells");
            if (model.closed) return this;
            long[] keys = new long[cells.size()];
            int n = 0;
            for (BlockPosition p : cells) if (inWorld(p)) keys[n++] = cell(p);
            rt.store.removeAll(model, keys, n);
            return this;
        }

        @Override public ClientBlockSet clearAll() {
            thread();
            if (!model.closed) rt.store.clear(model);
            return this;
        }

        @Override public ClientBlockSet expireAfter(long ticks) {
            thread();
            if (!model.closed) rt.store.expireAfter(model, ticks);
            return this;
        }

        @Override public ClientBlockSet budget(int cellsPerTick) {
            thread();
            rt.store.budget(model, cellsPerTick);
            return this;
        }

        @Override public ClientBlockSet collision(boolean changesCollision) {
            thread();
            if (model.closed) return this;
            rt.store.collision(model, changesCollision);
            if (changesCollision) rt.declaredCollision(this);
            return this;
        }

        @Override public ClientBlockSet onInteract(Consumer<ClientBlockInteract> listener) {
            thread();
            this.listener = listener;
            return this;
        }

        @Override public int pending() { thread(); return model.closed ? 0 : rt.store.pending(model); }
        @Override public int size() { return model.size; }
        @Override public boolean covers(BlockPosition cell) { return !model.closed && model.covers(cell.x(), cell.y(), cell.z()); }
        @Override public boolean isClosed() { return model.closed; }

        @Override public void close() {
            thread();
            if (!model.closed) rt.closeSet(this);
        }

        @Override public String toString() { return "ClientBlockSet[" + owner.getName() + ":" + model.name + " p" + model.priority + " " + model.size + "]"; }
    }

    static final class Interact implements ClientBlockInteract {
        final Player player;
        final Handle set;
        final BlockPosition cell;
        final BlockData shown;
        final Action action;
        boolean repair;

        Interact(Player player, Handle set, BlockPosition cell, BlockData shown, Action action) {
            this.player = player;
            this.set = set;
            this.cell = cell;
            this.shown = shown;
            this.action = action;
        }

        @Override public Player player() { return player; }
        @Override public ClientBlockSet set() { return set; }
        @Override public BlockPosition cell() { return cell; }
        @Override public BlockData shown() { return shown.clone(); }
        @Override public Action action() { return action; }
        @Override public void repair() { repair = true; }
    }
}
