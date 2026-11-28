package Vystorm.vystorm_core.packet.internal.blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content and settings of one client block set: cells per section (local index to state id), priority, order of
 * creation, owner name, budget, expiry, collision flag, and the viewers it is attached to (one for a per-player set,
 * any number for a shared one). Changed only through {@link ClientBlockStore}. Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class BlockSetModel {
    final String owner;
    final String name;
    final int priority;
    /** Creation order; on equal priority the newer (higher) set is shown. */
    final long order;
    final boolean shared;
    final Map<Long, CellMap> sections = new HashMap<>();
    final List<ViewerModel> viewers = new ArrayList<>(1);
    int size;
    boolean collision;
    /** Hold back solid cells inside the viewer's box (false for Visuals block views, which never did). */
    public boolean boxRule = true;
    int budget = ClientBlockStore.DEFAULT_SET_BUDGET;
    /** Tick at which the set closes itself; 0 = never. */
    long expiresAt;
    boolean closed;
    /** Cells this set may still send in tick {@link #budgetTick}. */
    int budgetLeft;
    long budgetTick = Long.MIN_VALUE;
    /** The runtime's handle for this set (the public {@code ClientBlockSet}). */
    public Object handle;

    BlockSetModel(String owner, String name, int priority, long order, boolean shared) {
        this.owner = owner;
        this.name = name;
        this.priority = priority;
        this.order = order;
        this.shared = shared;
    }

    public String owner() { return owner; }
    public String name() { return name; }
    public int priority() { return priority; }
    public boolean shared() { return shared; }
    public boolean collision() { return collision; }
    public int size() { return size; }
    public boolean closed() { return closed; }
    public long expiresAt() { return expiresAt; }
    public int budget() { return budget; }
    /** Viewers this set is attached to now (ids). */
    public List<java.util.UUID> viewerIds() {
        List<java.util.UUID> ids = new ArrayList<>(viewers.size());
        for (ViewerModel v : viewers) ids.add(v.id);
        return ids;
    }

    /** State id at a cell, or {@link CellMap#MISSING}. */
    int get(long section, int local) {
        CellMap cells = sections.get(section);
        return cells == null ? CellMap.MISSING : cells.get(local);
    }

    public boolean covers(int x, int y, int z) {
        return get(CellKeys.sectionOf(x, y, z), CellKeys.local(x, y, z)) != CellMap.MISSING;
    }

    /** State id at a cell, or -1 when the set does not cover it. */
    public int state(int x, int y, int z) {
        int state = get(CellKeys.sectionOf(x, y, z), CellKeys.local(x, y, z));
        return state == CellMap.MISSING ? -1 : state;
    }

    /** Shown above {@code other} where both cover a cell. */
    boolean above(BlockSetModel other) {
        return priority != other.priority ? priority > other.priority : order > other.order;
    }

    int takeBudget(long tick) {
        if (budgetTick != tick) {
            budgetTick = tick;
            budgetLeft = budget;
        }
        return budgetLeft;
    }
}
