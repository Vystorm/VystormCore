package Vystorm.vystorm_core.packet.internal.blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bookkeeping of one viewer in one world: the attached sets in shown order, the states Core sent and has not
 * restored, the cells that may need a packet (dirty, per section as a 4096-bit set), cells a block view wants sent in
 * the same call (urgent), and repairs waiting for their tick. Changed only through {@link ClientBlockStore}.
 * Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ViewerModel {
    final UUID id;
    final UUID world;
    final int minSectionY;
    final int maxSectionY;
    /** Shown order: first wins (priority high to low, newer first on equal priority). */
    final List<BlockSetModel> sets = new ArrayList<>(2);
    final Map<Long, CellMap> sent = new HashMap<>();
    int sentCount;
    final Map<Long, long[]> dirty = new HashMap<>();
    int dirtyCount;
    final Map<Long, long[]> urgent = new HashMap<>();
    /** Number of attached sets with cells in a section. */
    final LongIntMap sectionRefs = new LongIntMap();
    /** Cells of all attached sets together (limit {@link ClientBlockStore#MAX_VIEWER_CELLS}). */
    int cells;
    // Repair ring: cell keys and the tick they are due at, in due order (the delay is constant).
    long[] repairCells = new long[16];
    long[] repairDue = new long[16];
    int repairHead;
    int repairSize;
    /** Eye position for nearest-first sending, set by the environment before each flush. */
    public double eyeX, eyeY, eyeZ;
    /** The runtime's handle (the player). */
    public Object handle;

    ViewerModel(UUID id, UUID world, int minSectionY, int maxSectionY) {
        this.id = id;
        this.world = world;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
    }

    public UUID id() { return id; }
    public UUID world() { return world; }
    public int cells() { return cells; }
    public int sentCount() { return sentCount; }
    public int dirtyCount() { return dirtyCount; }
    public int repairsWaiting() { return repairSize; }
    public List<BlockSetModel> sets() { return List.copyOf(sets); }

    /** State Core sent to this cell and has not restored, or -1. */
    public int shown(int x, int y, int z) {
        CellMap s = sent.get(CellKeys.sectionOf(x, y, z));
        int state = s == null ? CellMap.MISSING : s.get(CellKeys.local(x, y, z));
        return state == CellMap.MISSING ? -1 : state;
    }

    /** The set whose cell is shown here (first in shown order that covers it), or null. */
    BlockSetModel winner(long section, int local) {
        for (BlockSetModel set : sets) if (set.get(section, local) != CellMap.MISSING) return set;
        return null;
    }

    void markDirty(long section, int local) {
        long[] bits = dirty.computeIfAbsent(section, ignored -> new long[64]);
        long bit = 1L << (local & 63);
        if ((bits[local >>> 6] & bit) == 0) {
            bits[local >>> 6] |= bit;
            dirtyCount++;
        }
    }

    void markUrgent(long section, int local) {
        markDirty(section, local);
        long[] bits = urgent.computeIfAbsent(section, ignored -> new long[64]);
        bits[local >>> 6] |= 1L << (local & 63);
    }

    /** Clears the dirty (and urgent) bit of a cell. */
    void clean(long section, int local, long[] bits) {
        long bit = 1L << (local & 63);
        if ((bits[local >>> 6] & bit) != 0) {
            bits[local >>> 6] &= ~bit;
            dirtyCount--;
        }
        long[] u = urgent.get(section);
        if (u != null) u[local >>> 6] &= ~bit;
    }

    void dropEmptyDirty(long section, long[] bits) {
        for (long word : bits) if (word != 0) return;
        dirty.remove(section);
        urgent.remove(section);
    }

    int sentState(long section, int local) {
        CellMap s = sent.get(section);
        return s == null ? CellMap.MISSING : s.get(local);
    }

    void putSent(long section, int local, int state) {
        CellMap s = sent.computeIfAbsent(section, ignored -> new CellMap());
        if (s.put(local, state) == CellMap.MISSING) sentCount++;
    }

    void removeSent(long section, int local) {
        CellMap s = sent.get(section);
        if (s == null) return;
        if (s.remove(local) != CellMap.MISSING) sentCount--;
        if (s.isEmpty()) sent.remove(section);
    }

    void dropSentSection(long section) {
        CellMap s = sent.remove(section);
        if (s != null) sentCount -= s.size();
    }

    void addRepair(long cell, long due) {
        if (repairSize == repairCells.length) {
            int n = repairCells.length * 2;
            long[] cells = new long[n], dues = new long[n];
            for (int i = 0; i < repairSize; i++) {
                cells[i] = repairCells[(repairHead + i) % repairCells.length];
                dues[i] = repairDue[(repairHead + i) % repairCells.length];
            }
            repairCells = cells;
            repairDue = dues;
            repairHead = 0;
        }
        int at = (repairHead + repairSize) % repairCells.length;
        repairCells[at] = cell;
        repairDue[at] = due;
        repairSize++;
    }

    boolean active() { return dirtyCount > 0 || repairSize > 0; }
}
