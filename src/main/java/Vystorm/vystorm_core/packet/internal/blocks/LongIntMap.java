package Vystorm.vystorm_core.packet.internal.blocks;

/**
 * Long keys to int counts without boxing (open addressing, backward-shift removal). Used as the per-world section
 * index that block events probe: one lookup per event, no allocation. A count that reaches 0 removes its key.
 * {@link Long#MIN_VALUE} is never a section key (it lies beyond the world border) and marks free slots.
 * Not thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class LongIntMap {
    private static final long FREE = Long.MIN_VALUE;
    private long[] keys;
    private int[] values;
    private int size;

    LongIntMap() {
        keys = new long[16];
        java.util.Arrays.fill(keys, FREE);
        values = new int[16];
    }

    int size() { return size; }

    int get(long key) {
        int mask = keys.length - 1;
        for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == FREE) return 0;
            if (k == key) return values[i];
        }
    }

    /** Adds {@code delta} to the count of {@code key}; a count of 0 or less removes the key. Returns the new count. */
    int add(long key, int delta) {
        if (key == FREE) throw new IllegalArgumentException("reserved key");
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == FREE) break;
            if (k == key) {
                int next = values[i] + delta;
                if (next <= 0) {
                    removeAt(i);
                    return 0;
                }
                values[i] = next;
                return next;
            }
            i = (i + 1) & mask;
        }
        if (delta <= 0) return 0;
        if ((size + 1) * 2 > keys.length) {
            grow();
            return add(key, delta);
        }
        keys[i] = key;
        values[i] = delta;
        size++;
        return delta;
    }

    /** Calls {@code action} for every key (do not change the map meanwhile). */
    void forEachKey(java.util.function.LongConsumer action) {
        for (long k : keys) if (k != FREE) action.accept(k);
    }

    private void removeAt(int i) {
        int mask = keys.length - 1;
        int hole = i;
        for (int j = (i + 1) & mask; keys[j] != FREE; j = (j + 1) & mask) {
            int home = mix(keys[j]) & mask;
            boolean between = hole <= j ? (home <= hole || home > j) : (home <= hole && home > j);
            if (between) {
                keys[hole] = keys[j];
                values[hole] = values[j];
                hole = j;
            }
        }
        keys[hole] = FREE;
        size--;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        java.util.Arrays.fill(keys, FREE);
        values = new int[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) if (oldKeys[i] != FREE) add(oldKeys[i], oldValues[i]);
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
