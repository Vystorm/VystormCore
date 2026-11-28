package Vystorm.vystorm_core.packet.internal.blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * The bookkeeping of client block sets without Bukkit: sets with priorities, which cell each viewer is shown, what
 * Core sent and has not restored, budgets, repairs, chunk (re)loads, expiry and limits. The runtime feeds events in
 * and hands {@link Env} for everything that needs the server (chunk held by the client, bounding box, real blocks,
 * sending).
 *
 * <p><b>Threads:</b> not thread-safe; the runtime calls every method on the server thread.
 *
 * <p><b>Sending:</b> {@link #tick} visits only viewers with work (dirty cells or due repairs). Per viewer it groups the
 * dirty cells by section, nearest section to the eye first, and hands one batch per section to {@link Env#send}. A
 * viewer gets at most {@code viewerBudget} cells per tick, all viewers together {@code totalBudget}; a quarter of the
 * viewer budget is kept for restores so a moving preview cannot starve its own clean-up; each set has its own cap per
 * tick ({@link BlockSetModel#budget}).
 *
 * <p><b>Repairs:</b> a real block change at a cell a viewer is shown a client block at reaches that client in the
 * server's chunk broadcast, which runs inside the level tick after the scheduler heartbeat; a change made after that
 * broadcast (pistons, entities, explosions, player actions between ticks) goes out in the NEXT tick's broadcast. Core
 * therefore re-sends the cell {@link #REPAIR_DELAY} heartbeats after the event, after every possible real update.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ClientBlockStore {
    public static final int MAX_SET_CELLS = 32_768;
    public static final int MAX_VIEWER_CELLS = 131_072;
    public static final int MAX_SETS_PER_VIEWER = 64;
    public static final int DEFAULT_SET_BUDGET = 4_096;
    public static final int DEFAULT_VIEWER_BUDGET = 8_192;
    public static final int DEFAULT_TOTAL_BUDGET = 32_768;
    /** Heartbeats between a real block change and the re-send of the client block (see class Javadoc). */
    public static final int REPAIR_DELAY = 2;
    /** State in a batch that means "send the real block". */
    public static final int REAL = -1;

    /** What the store needs from the server. Every method is called on the server thread. */
    public interface Env {
        /** Whether the viewer's client holds this chunk now. */
        boolean hasChunk(ViewerModel viewer, int chunkX, int chunkZ);
        /** Whether the cell overlaps the viewer's own bounding box. */
        boolean inBox(ViewerModel viewer, int x, int y, int z);
        /** Whether the real block at the cell has a collision box. */
        boolean realSolid(ViewerModel viewer, int x, int y, int z);
        /** Whether the state (an id of the runtime) has a collision box. */
        boolean solid(int state);
        /**
         * Sets {@code viewer.eyeX/Y/Z} before a flush. False when the viewer is gone or no longer in the model's
         * world: the store then forgets the viewer without sending anything.
         */
        boolean locate(ViewerModel viewer);
        /** Sends {@code count} cells of one section; a state {@link #REAL} means the real block of that cell. */
        void send(ViewerModel viewer, long section, short[] locals, int[] states, int count);
    }

    /** Counters since start (sets/viewers/cells are the current state). */
    public record Counters(long cellsSent, long batches, long singleBatches, long restores, long repairs, long blocked,
                           long refused, long expired, long chunkReloads) {}

    private int viewerBudget;
    private int totalBudget;
    private final Map<UUID, ViewerModel> viewers = new HashMap<>();
    private final Map<UUID, List<ViewerModel>> byWorld = new HashMap<>();
    private final Map<UUID, LongIntMap> worldIndex = new HashMap<>();
    private final LinkedHashSet<ViewerModel> active = new LinkedHashSet<>();
    private final List<BlockSetModel> sets = new ArrayList<>();
    private final PriorityQueue<BlockSetModel> expiry = new PriorityQueue<>(Comparator.comparingLong(s -> s.expiresAt));
    private long order;
    private long tick;
    private int roundRobin;
    private long cellsSent, batches, singleBatches, restores, repairs, blocked, refused, expired, chunkReloads;

    public ClientBlockStore() { this(DEFAULT_VIEWER_BUDGET, DEFAULT_TOTAL_BUDGET); }

    public ClientBlockStore(int viewerBudget, int totalBudget) { budgets(viewerBudget, totalBudget); }

    public void budgets(int viewerBudget, int totalBudget) {
        this.viewerBudget = Math.max(1, Math.min(MAX_VIEWER_CELLS, viewerBudget));
        this.totalBudget = Math.max(this.viewerBudget, Math.min(1_048_576, totalBudget));
    }

    public int viewerBudget() { return viewerBudget; }
    public int totalBudget() { return totalBudget; }
    public long tickNumber() { return tick; }
    public List<BlockSetModel> openSets() { return List.copyOf(sets); }
    public int openSetCount() { return sets.size(); }
    public boolean idle() { return active.isEmpty() && expiry.isEmpty(); }
    public int activeViewers() { return active.size(); }
    public java.util.Collection<ViewerModel> viewers() { return List.copyOf(viewers.values()); }

    public Counters counters() {
        return new Counters(cellsSent, batches, singleBatches, restores, repairs, blocked, refused, expired, chunkReloads);
    }

    public void countRefused() { refused++; }

    // ------------------------------------------------------------------ viewers

    /** The viewer's model in this world; a model of another world is dropped first (its sets close). */
    public ViewerModel viewer(UUID id, UUID world, int minSectionY, int maxSectionY) {
        ViewerModel v = viewers.get(id);
        if (v != null && v.world.equals(world)) return v;
        if (v != null) dropViewer(id);
        v = new ViewerModel(id, world, minSectionY, maxSectionY);
        viewers.put(id, v);
        byWorld.computeIfAbsent(world, ignored -> new ArrayList<>()).add(v);
        return v;
    }

    public ViewerModel find(UUID id) { return viewers.get(id); }

    /**
     * Forgets a viewer whose client dropped its world (quit, world change, respawn): nothing is sent. Its per-player
     * sets are closed, shared sets only lose this viewer. Returns the sets that closed.
     */
    public List<BlockSetModel> dropViewer(UUID id) {
        ViewerModel v = viewers.remove(id);
        if (v == null) return List.of();
        List<BlockSetModel> closed = new ArrayList<>();
        for (BlockSetModel set : List.copyOf(v.sets)) {
            set.viewers.remove(v);
            if (!set.shared) {
                set.closed = true;
                sets.remove(set);
                expiry.remove(set);
                forgetContent(set);
                closed.add(set);
            }
        }
        v.sets.clear();
        LongIntMap index = worldIndex.get(v.world);
        if (index != null) v.sectionRefs.forEachKey(section -> index.add(section, -1));
        List<ViewerModel> list = byWorld.get(v.world);
        if (list != null) {
            list.remove(v);
            if (list.isEmpty()) byWorld.remove(v.world);
        }
        active.remove(v);
        return closed;
    }

    // ------------------------------------------------------------------ sets

    public BlockSetModel open(String owner, String name, int priority, boolean shared) {
        BlockSetModel set = new BlockSetModel(owner, name, priority, ++order, shared);
        sets.add(set);
        return set;
    }

    /** Attaches a set to a viewer; false (counted as refused) when the viewer is at a limit or the set is closed. */
    public boolean attach(BlockSetModel set, ViewerModel viewer) {
        if (set.closed || viewers.get(viewer.id) != viewer) return false;
        if (viewer.sets.contains(set)) return true;
        if (viewer.sets.size() >= MAX_SETS_PER_VIEWER || viewer.cells + set.size > MAX_VIEWER_CELLS) {
            refused++;
            return false;
        }
        int at = 0;
        while (at < viewer.sets.size() && !set.above(viewer.sets.get(at))) at++;
        viewer.sets.add(at, set);
        set.viewers.add(viewer);
        viewer.cells += set.size;
        for (Map.Entry<Long, CellMap> e : set.sections.entrySet()) {
            sectionRef(viewer, e.getKey(), 1);
            markAll(viewer, e.getKey(), e.getValue());
        }
        activate(viewer);
        return true;
    }

    /** Detaches a set from a viewer; its cells fall back to the next set below or the real block. */
    public void detach(BlockSetModel set, ViewerModel viewer) {
        if (!viewer.sets.remove(set)) return;
        set.viewers.remove(viewer);
        viewer.cells -= set.size;
        for (Map.Entry<Long, CellMap> e : set.sections.entrySet()) {
            sectionRef(viewer, e.getKey(), -1);
            markAll(viewer, e.getKey(), e.getValue());
        }
        activate(viewer);
    }

    /** Closes a set; every viewer gets the next lower set's block or the real one (within budget). */
    public void close(BlockSetModel set) {
        if (set.closed) return;
        set.closed = true;
        for (ViewerModel v : List.copyOf(set.viewers)) detach(set, v);
        sets.remove(set);
        expiry.remove(set);
        forgetContent(set);
    }

    /** A closed set keeps no cells (a plugin that still holds the handle holds no cell data). */
    private static void forgetContent(BlockSetModel set) {
        set.sections.clear();
        set.size = 0;
        set.viewers.clear();
    }

    /** Closes the set after {@code ticks} ticks; 0 = never. */
    public void expireAfter(BlockSetModel set, long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks < 0");
        expiry.remove(set);
        set.expiresAt = ticks == 0 ? 0 : tick + ticks;
        if (ticks > 0 && !set.closed) expiry.add(set);
    }

    public void budget(BlockSetModel set, int cellsPerTick) {
        if (cellsPerTick < 1 || cellsPerTick > MAX_SET_CELLS)
            throw new IllegalArgumentException("budget must be 1.." + MAX_SET_CELLS);
        set.budget = cellsPerTick;
    }

    public void collision(BlockSetModel set, boolean changes) {
        if (set.collision == changes) return;
        set.collision = changes;
        for (ViewerModel v : set.viewers) {
            for (Map.Entry<Long, CellMap> e : set.sections.entrySet()) markAll(v, e.getKey(), e.getValue());
            activate(v);
        }
    }

    /** Adds or changes cells ({@code cells[i]} from {@link CellKeys#cell}). All or nothing at the limits. */
    public void putAll(BlockSetModel set, long[] cells, int[] states, int count) {
        open(set);
        int added = 0;
        long lastSection = Long.MIN_VALUE;
        CellMap last = null;
        for (int i = 0; i < count; i++) {
            if (states[i] < 0) throw new IllegalArgumentException("state id < 0");
            long c = cells[i];
            long section = sectionOfCell(c);
            if (section != lastSection) {
                lastSection = section;
                last = set.sections.get(section);
            }
            if (last == null || !last.contains(localOfCell(c))) added++;
        }
        checkGrowth(set, added, set.size + added);
        // One map lookup per run of cells in the same section, not per cell.
        int nv = set.viewers.size();
        long[][] dirtyBits = new long[nv][];
        lastSection = Long.MIN_VALUE;
        CellMap m = null;
        for (int i = 0; i < count; i++) {
            long section = sectionOfCell(cells[i]);
            int local = localOfCell(cells[i]);
            if (section != lastSection) {
                lastSection = section;
                m = set.sections.get(section);
                if (m == null) {
                    m = new CellMap(64);
                    set.sections.put(section, m);
                    for (ViewerModel v : set.viewers) sectionRef(v, section, 1);
                }
                java.util.Arrays.fill(dirtyBits, null);
            }
            int old = m.put(local, states[i]);
            if (old == states[i]) continue;
            if (old == CellMap.MISSING) {
                set.size++;
                for (int k = 0; k < nv; k++) set.viewers.get(k).cells++;
            }
            long bit = 1L << (local & 63);
            for (int k = 0; k < nv; k++) {
                long[] bits = dirtyBits[k];
                if (bits == null) bits = dirtyBits[k] = set.viewers.get(k).dirty.computeIfAbsent(section, ignored -> new long[64]);
                if ((bits[local >>> 6] & bit) == 0) {
                    bits[local >>> 6] |= bit;
                    set.viewers.get(k).dirtyCount++;
                }
            }
        }
        for (ViewerModel v : set.viewers) {
            activate(v);
        }
    }


    public void put(BlockSetModel set, int x, int y, int z, int state) {
        putAll(set, new long[]{CellKeys.cell(x, y, z)}, new int[]{state}, 1);
    }

    /** Removes cells; the next lower set's block or the real one is shown there. */
    public void removeAll(BlockSetModel set, long[] cells, int count) {
        open(set);
        for (int i = 0; i < count; i++) removeCell(set, sectionOfCell(cells[i]), localOfCell(cells[i]));
    }

    public void clear(BlockSetModel set) {
        open(set);
        for (Map.Entry<Long, CellMap> e : List.copyOf(set.sections.entrySet())) {
            CellMap m = e.getValue();
            int[] locals = locals(m);
            for (int local : locals) removeCell(set, e.getKey(), local);
        }
    }

    /** Replaces the whole content with exactly these cells (unchanged cells cost nothing). All or nothing. */
    public void replace(BlockSetModel set, long[] cells, int[] states, int count) {
        open(set);
        Map<Long, CellMap> next = new HashMap<>();
        int size = 0;
        for (int i = 0; i < count; i++) {
            if (states[i] < 0) throw new IllegalArgumentException("state id < 0");
            CellMap m = next.computeIfAbsent(sectionOfCell(cells[i]), ignored -> new CellMap());
            if (m.put(localOfCell(cells[i]), states[i]) == CellMap.MISSING) size++;
        }
        checkGrowth(set, size - set.size, size);
        for (Map.Entry<Long, CellMap> e : List.copyOf(set.sections.entrySet())) {
            CellMap wanted = next.get(e.getKey());
            for (int local : locals(e.getValue())) if (wanted == null || !wanted.contains(local)) removeCell(set, e.getKey(), local);
        }
        for (Map.Entry<Long, CellMap> e : next.entrySet()) {
            CellMap m = e.getValue();
            for (int slot = 0; slot < m.slots(); slot++) {
                int local = m.localAt(slot);
                if (local >= 0) putCell(set, e.getKey(), local, m.valueAt(slot));
            }
        }
    }

    /** Cells of the set still waiting for a packet in any attached viewer (budget, chunk not held, refused). */
    public int pending(BlockSetModel set) {
        int n = 0;
        for (ViewerModel v : set.viewers)
            for (Map.Entry<Long, CellMap> e : set.sections.entrySet()) {
                long[] bits = v.dirty.get(e.getKey());
                if (bits == null) continue;
                CellMap m = e.getValue();
                for (int slot = 0; slot < m.slots(); slot++) {
                    int local = m.localAt(slot);
                    if (local >= 0 && (bits[local >>> 6] & (1L << (local & 63))) != 0) n++;
                }
            }
        return n;
    }

    private static void open(BlockSetModel set) {
        if (set.closed) throw new IllegalStateException("Client block set is closed");
    }

    private void checkGrowth(BlockSetModel set, int added, int newSize) {
        if (newSize > MAX_SET_CELLS) {
            refused++;
            throw new IllegalArgumentException("A client block set holds at most " + MAX_SET_CELLS + " cells (asked " + newSize + ")");
        }
        for (ViewerModel v : List.copyOf(set.viewers))
            if (v.cells + added > MAX_VIEWER_CELLS) {
                refused++;
                if (set.shared) {
                    // A shared set keeps growing for everyone else; this viewer no longer gets it.
                    detach(set, v);
                    continue;
                }
                throw new IllegalArgumentException("A viewer is shown at most " + MAX_VIEWER_CELLS + " client blocks (asked " + (v.cells + added) + ")");
            }
    }

    private void putCell(BlockSetModel set, long section, int local, int state) {
        CellMap m = set.sections.get(section);
        boolean newSection = m == null;
        if (newSection) {
            m = new CellMap();
            set.sections.put(section, m);
        }
        int old = m.put(local, state);
        if (old == state) return;
        if (old == CellMap.MISSING) {
            set.size++;
            for (ViewerModel v : set.viewers) v.cells++;
        }
        for (ViewerModel v : set.viewers) {
            if (newSection) sectionRef(v, section, 1);
            v.markDirty(section, local);
            activate(v);
        }
    }

    private void removeCell(BlockSetModel set, long section, int local) {
        CellMap m = set.sections.get(section);
        if (m == null || m.remove(local) == CellMap.MISSING) return;
        set.size--;
        boolean emptied = m.isEmpty();
        if (emptied) set.sections.remove(section);
        for (ViewerModel v : set.viewers) {
            v.cells--;
            if (emptied) sectionRef(v, section, -1);
            v.markDirty(section, local);
            activate(v);
        }
    }

    private void markAll(ViewerModel v, long section, CellMap m) {
        for (int slot = 0; slot < m.slots(); slot++) {
            int local = m.localAt(slot);
            if (local >= 0) v.markDirty(section, local);
        }
    }

    private void sectionRef(ViewerModel v, long section, int delta) {
        int before = v.sectionRefs.get(section);
        int after = v.sectionRefs.add(section, delta);
        if (before == 0 && after > 0) worldIndex.computeIfAbsent(v.world, ignored -> new LongIntMap()).add(section, 1);
        else if (before > 0 && after == 0) {
            LongIntMap index = worldIndex.get(v.world);
            if (index != null) index.add(section, -1);
        }
    }

    private void activate(ViewerModel v) { if (v.active() && viewers.get(v.id) == v) active.add(v); }

    private static int[] locals(CellMap m) {
        int[] out = new int[m.size()];
        int n = 0;
        for (int slot = 0; slot < m.slots(); slot++) {
            int local = m.localAt(slot);
            if (local >= 0) out[n++] = local;
        }
        return out;
    }

    static long sectionOfCell(long cell) {
        return CellKeys.sectionOf(CellKeys.cellX(cell), CellKeys.cellY(cell), CellKeys.cellZ(cell));
    }

    static int localOfCell(long cell) {
        return CellKeys.local(CellKeys.cellX(cell), CellKeys.cellY(cell), CellKeys.cellZ(cell));
    }

    // ------------------------------------------------------------------ events

    /** Whether any viewer in this world is shown client blocks in the section of this cell (one probe, no allocation). */
    public boolean watched(UUID world, int x, int y, int z) {
        LongIntMap index = worldIndex.get(world);
        return index != null && index.get(CellKeys.sectionOf(x, y, z)) > 0;
    }

    /**
     * A real block changed (or may have changed) at a cell: every viewer shown a client block there gets it again
     * {@link #REPAIR_DELAY} ticks from now.
     */
    public void realChanged(UUID world, int x, int y, int z) { realChanged(world, x, y, z, null); }

    /** As {@link #realChanged(UUID, int, int, int)}, but not for {@code except} (null: everyone). */
    public void realChanged(UUID world, int x, int y, int z, UUID except) {
        if (!watched(world, x, y, z)) return;
        List<ViewerModel> list = byWorld.get(world);
        if (list == null) return;
        long section = CellKeys.sectionOf(x, y, z);
        int local = CellKeys.local(x, y, z);
        for (ViewerModel v : list)
            if (!v.id.equals(except) && v.sectionRefs.get(section) > 0 && v.winner(section, local) != null) queueRepair(v, x, y, z);
    }

    /** Whether any viewer in this world is shown client blocks somewhere in this chunk column. */
    public boolean watchedChunk(UUID world, int chunkX, int chunkZ, int minSectionY, int maxSectionY) {
        LongIntMap index = worldIndex.get(world);
        if (index == null) return false;
        for (int sy = minSectionY; sy <= maxSectionY; sy++) if (index.get(CellKeys.section(chunkX, sy, chunkZ)) > 0) return true;
        return false;
    }

    /**
     * The viewer's client shows the real block at this cell now and Core leaves it so (an interact listener took
     * over): the cell is no longer counted as shown until it changes again.
     */
    public void forgetShown(UUID viewer, int x, int y, int z) {
        ViewerModel v = viewers.get(viewer);
        if (v != null) v.removeSent(CellKeys.sectionOf(x, y, z), CellKeys.local(x, y, z));
    }

    /** One viewer's client was told the real block at a cell (a refused click or break): repair it for that viewer. */
    public void repair(UUID viewer, int x, int y, int z) {
        ViewerModel v = viewers.get(viewer);
        if (v == null) return;
        long section = CellKeys.sectionOf(x, y, z);
        if (v.sectionRefs.get(section) > 0 && v.winner(section, CellKeys.local(x, y, z)) != null) queueRepair(v, x, y, z);
    }

    private void queueRepair(ViewerModel v, int x, int y, int z) {
        v.addRepair(CellKeys.cell(x, y, z), tick + REPAIR_DELAY);
        activate(v);
    }

    /** The set whose client block the viewer is shown at a cell (Core sent it and has not restored), or null. */
    public BlockSetModel shownBy(UUID viewer, int x, int y, int z) {
        ViewerModel v = viewers.get(viewer);
        if (v == null) return null;
        long section = CellKeys.sectionOf(x, y, z);
        int local = CellKeys.local(x, y, z);
        if (v.sentState(section, local) == CellMap.MISSING) return null;
        return v.winner(section, local);
    }

    /** The server sent this chunk to the viewer again: its real blocks replaced every client block in it. */
    public void chunkLoaded(UUID viewer, int chunkX, int chunkZ) {
        ViewerModel v = viewers.get(viewer);
        if (v == null) return;
        boolean any = false;
        for (int sy = v.minSectionY; sy <= v.maxSectionY; sy++) {
            long section = CellKeys.section(chunkX, sy, chunkZ);
            if (v.sectionRefs.get(section) == 0) continue;
            any = true;
            v.dropSentSection(section);
            for (BlockSetModel set : v.sets) {
                CellMap m = set.sections.get(section);
                if (m != null) markAll(v, section, m);
            }
        }
        if (any) {
            chunkReloads++;
            activate(v);
        }
    }

    /** The viewer's client forgot this chunk: nothing of it is shown any more. */
    public void chunkUnloaded(UUID viewer, int chunkX, int chunkZ) {
        ViewerModel v = viewers.get(viewer);
        if (v == null) return;
        for (int sy = v.minSectionY; sy <= v.maxSectionY; sy++) {
            long section = CellKeys.section(chunkX, sy, chunkZ);
            if (v.sectionRefs.get(section) == 0 && !v.sent.containsKey(section)) continue;
            v.dropSentSection(section);
            long[] bits = v.dirty.remove(section);
            if (bits != null) for (long word : bits) v.dirtyCount -= Long.bitCount(word);
            v.urgent.remove(section);
        }
    }

    // ------------------------------------------------------------------ sending

    /** One server tick: expiry, due repairs, then sending within the budgets. */
    public void tick(long now, Env env) {
        tick = now;
        while (!expiry.isEmpty() && expiry.peek().expiresAt <= now) {
            BlockSetModel set = expiry.poll();
            expired++;
            close(set);
        }
        if (active.isEmpty()) return;
        List<ViewerModel> order = new ArrayList<>(active);
        int n = order.size();
        int start = Math.floorMod(roundRobin++, n);
        int total = totalBudget;
        for (int i = 0; i < n; i++) {
            ViewerModel v = order.get((start + i) % n);
            if (viewers.get(v.id) != v) {
                active.remove(v);
                continue;
            }
            dueRepairs(v, now);
            if (v.dirtyCount > 0 && total > 0) total -= flush(v, Math.min(viewerBudget, total), false, env);
            if (!v.active()) active.remove(v);
        }
    }

    /**
     * Sends the viewer's urgent cells now, without a budget (a block view's own call already limited them).
     * Other dirty cells wait for the tick.
     */
    public void flushUrgent(UUID viewer, Env env) {
        ViewerModel v = viewers.get(viewer);
        if (v == null || v.urgent.isEmpty()) return;
        flush(v, Integer.MAX_VALUE, true, env);
        v.urgent.clear();
        if (!v.active()) active.remove(v);
    }

    /** Marks cells of a set as urgent in one viewer (they go out in the next {@link #flushUrgent}). */
    public void markUrgent(ViewerModel v, long[] cells, int count) {
        for (int i = 0; i < count; i++) v.markUrgent(sectionOfCell(cells[i]), localOfCell(cells[i]));
        activate(v);
    }

    /** Marks every dirty cell of the viewer urgent (Core shutdown: nothing may wait for a tick that never comes). */
    public void urgentAll(ViewerModel v) {
        for (Map.Entry<Long, long[]> e : v.dirty.entrySet()) v.urgent.put(e.getKey(), e.getValue().clone());
        activate(v);
    }

    private void dueRepairs(ViewerModel v, long now) {
        while (v.repairSize > 0 && v.repairDue[v.repairHead] <= now) {
            long cell = v.repairCells[v.repairHead];
            v.repairHead = (v.repairHead + 1) % v.repairCells.length;
            v.repairSize--;
            long section = sectionOfCell(cell);
            int local = localOfCell(cell);
            if (v.winner(section, local) == null) continue;
            v.removeSent(section, local);
            v.markDirty(section, local);
            repairs++;
        }
    }

    // Scratch op lists for one flush (server thread only).
    private int[] opSection = new int[256], opLocal = new int[256], opState = new int[256];
    private BlockSetModel[] opSet = new BlockSetModel[256];
    private int[] rSection = new int[256], rLocal = new int[256];
    private boolean[] taken = new boolean[256];
    private short[] batchLocals = new short[4096];
    private int[] batchStates = new int[4096];

    private int flush(ViewerModel v, int budget, boolean urgentOnly, Env env) {
        Map<Long, long[]> source = urgentOnly ? v.urgent : v.dirty;
        if (source.isEmpty()) return 0;
        if (!env.locate(v)) {
            dropViewer(v.id);
            return 0;
        }
        long[] secs = new long[source.size()];
        int sc = 0;
        for (Long key : source.keySet()) secs[sc++] = key;
        if (!urgentOnly && sc > 1) sortByDistance(v, secs, sc);
        int reserve = urgentOnly ? Integer.MAX_VALUE : Math.max(1, budget / 4);
        int adds = 0, rests = 0;
        BlockSetModel[] here = new BlockSetModel[v.sets.size()];
        CellMap[] maps = new CellMap[v.sets.size()];
        for (int s = 0; s < sc; s++) {
            long section = secs[s];
            long[] bits = v.dirty.get(section);
            if (bits == null) continue;
            long[] mask = urgentOnly ? v.urgent.get(section) : null;
            if (urgentOnly && mask == null) continue;
            boolean has = env.hasChunk(v, CellKeys.sectionX(section), CellKeys.sectionZ(section));
            CellMap sentMap = v.sent.get(section);
            long[] ubits = urgentOnly ? mask : v.urgent.get(section);
            int k = 0;
            for (BlockSetModel set : v.sets) {
                CellMap m = set.sections.get(section);
                if (m != null) {
                    here[k] = set;
                    maps[k++] = m;
                }
            }
            for (int w = 0; w < 64; w++) {
                long word = bits[w] & (mask == null ? -1L : mask[w]);
                while (word != 0) {
                    int local = (w << 6) | Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    int cur = sentMap == null ? CellMap.MISSING : sentMap.get(local);
                    if (!has) {
                        clean(v, bits, ubits, local);
                        if (cur != CellMap.MISSING) {
                            sentMap.remove(local);
                            v.sentCount--;
                        }
                        continue;
                    }
                    int target = REAL;
                    BlockSetModel winner = null;
                    for (int i = 0; i < k; i++) {
                        int st = maps[i].get(local);
                        if (st != CellMap.MISSING) {
                            target = st;
                            winner = here[i];
                            break;
                        }
                    }
                    if (winner == null) {
                        if (cur == CellMap.MISSING) {
                            clean(v, bits, ubits, local);
                            continue;
                        }
                        ensure(rests + 1);
                        rSection[rests] = s;
                        rLocal[rests++] = local;
                        continue;
                    }
                    if (cur == target) {
                        clean(v, bits, ubits, local);
                        continue;
                    }
                    if (!winner.collision && winner.boxRule && env.solid(target)) {
                        int x = CellKeys.blockX(section, local), y = CellKeys.blockY(section, local), z = CellKeys.blockZ(section, local);
                        if (env.inBox(v, x, y, z) && !env.realSolid(v, x, y, z)) {
                            blocked++;
                            if (ubits != null) ubits[local >>> 6] &= ~(1L << (local & 63));
                            continue;
                        }
                    }
                    ensure(adds + 1);
                    opSection[adds] = s;
                    opLocal[adds] = local;
                    opState[adds] = target;
                    opSet[adds++] = winner;
                }
            }
            if (sentMap != null && sentMap.isEmpty()) v.sent.remove(section);
            if (adds >= budget && rests >= budget) break;
        }
        // Choose what fits: restores up to the reserve, then additions (per-set caps), then more restores.
        int rTake = Math.min(rests, reserve);
        int aTaken = 0;
        int aRoom = budget == Integer.MAX_VALUE ? Integer.MAX_VALUE : budget - rTake;
        for (int i = 0; i < adds; i++) {
            taken[i] = false;
            if (aTaken >= aRoom) continue;
            BlockSetModel set = opSet[i];
            if (!urgentOnly) {
                if (set.takeBudget(tick) <= 0) continue;
                set.budgetLeft--;
            }
            taken[i] = true;
            aTaken++;
        }
        if (budget != Integer.MAX_VALUE) rTake += Math.min(rests - rTake, budget - rTake - aTaken);
        else rTake = rests;
        // Emit per section in scan order: restores and additions of a section share one batch.
        int sentHere = 0;
        int ai = 0, ri = 0;
        while (ai < adds || ri < rTake) {
            int s = Math.min(ai < adds ? opSection[ai] : Integer.MAX_VALUE, ri < rTake ? rSection[ri] : Integer.MAX_VALUE);
            long section = secs[s];
            long[] bits = v.dirty.get(section);
            long[] ubits = v.urgent.get(section);
            CellMap sentMap = v.sent.get(section);
            int n = 0, restored = 0;
            while (ri < rTake && rSection[ri] == s) {
                int local = rLocal[ri++];
                if (sentMap != null && sentMap.remove(local) != CellMap.MISSING) v.sentCount--;
                if (bits != null) clean(v, bits, ubits, local);
                batchLocals[n] = (short) local;
                batchStates[n++] = REAL;
                restored++;
            }
            while (ai < adds && opSection[ai] == s) {
                int i = ai++;
                if (!taken[i]) continue;
                if (sentMap == null) {
                    sentMap = new CellMap(64);
                    v.sent.put(section, sentMap);
                }
                if (sentMap.put(opLocal[i], opState[i]) == CellMap.MISSING) v.sentCount++;
                if (bits != null) clean(v, bits, ubits, opLocal[i]);
                batchLocals[n] = (short) opLocal[i];
                batchStates[n++] = opState[i];
            }
            if (sentMap != null && sentMap.isEmpty()) v.sent.remove(section);
            if (bits != null) v.dropEmptyDirty(section, bits);
            if (n == 0) continue;
            sentHere += n;
            cellsSent += n;
            restores += restored;
            batches++;
            if (n == 1) singleBatches++;
            env.send(v, section, batchLocals, batchStates, n);
        }
        for (int s = 0; s < sc; s++) {
            long[] bits = v.dirty.get(secs[s]);
            if (bits != null) v.dropEmptyDirty(secs[s], bits);
        }
        Arrays.fill(opSet, 0, Math.min(opSet.length, adds), null);
        return sentHere;
    }

    private static void clean(ViewerModel v, long[] bits, long[] ubits, int local) {
        long bit = 1L << (local & 63);
        int w = local >>> 6;
        if ((bits[w] & bit) != 0) {
            bits[w] &= ~bit;
            v.dirtyCount--;
        }
        if (ubits != null) ubits[w] &= ~bit;
    }

    private void ensure(int n) {
        if (n <= opSection.length && n <= rSection.length) return;
        int size = Math.max(n, opSection.length * 2);
        opSection = Arrays.copyOf(opSection, size);
        opLocal = Arrays.copyOf(opLocal, size);
        opState = Arrays.copyOf(opState, size);
        opSet = Arrays.copyOf(opSet, size);
        rSection = Arrays.copyOf(rSection, size);
        rLocal = Arrays.copyOf(rLocal, size);
        taken = Arrays.copyOf(taken, size);
    }

    private static void sortByDistance(ViewerModel v, long[] secs, int n) {
        long[] keyed = new long[n];
        for (int i = 0; i < n; i++) {
            long s = secs[i];
            double dx = (CellKeys.sectionX(s) << 4) + 8 - v.eyeX;
            double dy = (CellKeys.sectionY(s) << 4) + 8 - v.eyeY;
            double dz = (CellKeys.sectionZ(s) << 4) + 8 - v.eyeZ;
            long d = (long) Math.min(Integer.MAX_VALUE, dx * dx + dy * dy + dz * dz);
            keyed[i] = d << 32 | i;
        }
        Arrays.sort(keyed);
        long[] copy = Arrays.copyOf(secs, n);
        for (int i = 0; i < n; i++) secs[i] = copy[(int) (keyed[i] & 0xFFFFFFFFL)];
    }
}
