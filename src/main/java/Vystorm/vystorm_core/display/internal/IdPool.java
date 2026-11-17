package Vystorm.vystorm_core.display.internal;

import java.util.ArrayDeque;

/** Recycling allocator for a fixed id range {@code [first, first + count)}. Not thread-safe (server thread only). */
public final class IdPool {
    private final int first, count;
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private int next;

    public IdPool(int first, int count) {
        if (count <= 0 || (long) first + count - 1 > Integer.MAX_VALUE) throw new IllegalArgumentException("bad id range");
        this.first = first;
        this.count = count;
    }

    public int take() {
        Integer reused = free.pollFirst();
        if (reused != null) return reused;
        if (next >= count) throw new IllegalStateException("id range exhausted");
        return first + next++;
    }

    public void give(int id) {
        if (id < first || id - first >= next) throw new IllegalArgumentException("id " + id + " not from this pool");
        free.addLast(id);
    }

    public boolean contains(int id) { return id >= first && id - first < count; }

    /** Ids currently handed out. */
    public int used() { return next - free.size(); }
}
