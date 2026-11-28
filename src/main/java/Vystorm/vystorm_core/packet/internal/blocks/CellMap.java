package Vystorm.vystorm_core.packet.internal.blocks;

/**
 * The cells of one section: local index (0..4095) to an int value, open addressing with linear probing and
 * backward-shift removal, no boxing. About 12 bytes per cell at the usual fill. Not thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class CellMap {
    /** Returned for a cell that is not in the map. */
    static final int MISSING = Integer.MIN_VALUE;

    /** Local index + 1; 0 = free slot. */
    private char[] keys;
    private int[] values;
    private int size;

    CellMap() { this(8); }

    CellMap(int expected) {
        int capacity = 8;
        while (capacity < expected * 2 && capacity < 8192) capacity <<= 1;
        keys = new char[capacity];
        values = new int[capacity];
    }

    int size() { return size; }

    boolean isEmpty() { return size == 0; }

    int get(int local) {
        int mask = keys.length - 1;
        char key = (char) (local + 1);
        for (int i = mix(local) & mask; ; i = (i + 1) & mask) {
            char k = keys[i];
            if (k == 0) return MISSING;
            if (k == key) return values[i];
        }
    }

    boolean contains(int local) { return get(local) != MISSING; }

    /** @return the old value, or {@link #MISSING} when the cell was new */
    int put(int local, int value) {
        if (local < 0 || local > 4095) throw new IllegalArgumentException("local index " + local);
        if ((size + 1) * 2 > keys.length) grow();
        int mask = keys.length - 1;
        char key = (char) (local + 1);
        for (int i = mix(local) & mask; ; i = (i + 1) & mask) {
            char k = keys[i];
            if (k == 0) {
                keys[i] = key;
                values[i] = value;
                size++;
                return MISSING;
            }
            if (k == key) {
                int old = values[i];
                values[i] = value;
                return old;
            }
        }
    }

    /** @return the old value, or {@link #MISSING} when the cell was not there */
    int remove(int local) {
        int mask = keys.length - 1;
        char key = (char) (local + 1);
        int i = mix(local) & mask;
        while (true) {
            char k = keys[i];
            if (k == 0) return MISSING;
            if (k == key) break;
            i = (i + 1) & mask;
        }
        int old = values[i];
        // Backward shift: move later entries of the same probe run into the hole.
        int hole = i;
        for (int j = (i + 1) & mask; keys[j] != 0; j = (j + 1) & mask) {
            int home = mix(keys[j] - 1) & mask;
            boolean between = hole <= j ? (home <= hole || home > j) : (home <= hole && home > j);
            if (between) {
                keys[hole] = keys[j];
                values[hole] = values[j];
                hole = j;
            }
        }
        keys[hole] = 0;
        size--;
        return old;
    }

    /** Slot count for iteration with {@link #localAt} and {@link #valueAt}. */
    int slots() { return keys.length; }

    /** Local index in a slot, or -1 for a free slot. */
    int localAt(int slot) { return keys[slot] - 1; }

    int valueAt(int slot) { return values[slot]; }

    private void grow() {
        char[] oldKeys = keys;
        int[] oldValues = values;
        keys = new char[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) if (oldKeys[i] != 0) put(oldKeys[i] - 1, oldValues[i]);
    }

    private static int mix(int local) { return local * 0x9E3779B1 >>> 16; }
}
