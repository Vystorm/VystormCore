package Vystorm.vystorm_core.packet.internal.wire;

import java.util.Arrays;

/**
 * Bitsets of one 16x16x16 section: {@code long[64]}, cell {@code k = y << 8 | z << 4 | x} is word {@code k >>> 6},
 * bit {@code k & 63} (the same layout as Core's {@code SurveyMask} and {@code OpenChunk}). {@link #ALL} and
 * {@link #NONE} are shared constants: never write into them.
 */
public final class Cells {
    private Cells() {}

    public static final int WORDS = 64;
    public static final long[] ALL = filled(), NONE = new long[WORDS];

    private static long[] filled() { long[] a = new long[WORDS]; Arrays.fill(a, -1L); return a; }

    public static int index(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | (x & 15); }

    public static boolean bit(long[] m, int k) { return (m[k >>> 6] & (1L << (k & 63))) != 0; }

    public static void set(long[] m, int k) { m[k >>> 6] |= 1L << (k & 63); }

    public static boolean isAll(long[] m) {
        if (m == ALL) return true;
        if (m == NONE) return false;
        for (long w : m) if (w != -1L) return false;
        return true;
    }

    public static boolean isNone(long[] m) {
        if (m == NONE) return true;
        if (m == ALL) return false;
        for (long w : m) if (w != 0) return false;
        return true;
    }

    /** The shared constant for a uniform set, otherwise the array itself. */
    public static long[] share(long[] m) { return isAll(m) ? ALL : isNone(m) ? NONE : m; }

    /** a | b without changing either (shared constants kept where possible). */
    public static long[] or(long[] a, long[] b) {
        if (a == null || a == NONE) return b == null ? NONE : b;
        if (b == null || b == NONE || a == ALL) return a;
        if (b == ALL) return ALL;
        long[] out = new long[WORDS];
        for (int i = 0; i < WORDS; i++) out[i] = a[i] | b[i];
        return share(out);
    }

    public static int count(long[] m) {
        if (m == ALL) return 4096;
        if (m == NONE) return 0;
        int n = 0;
        for (long w : m) n += Long.bitCount(w);
        return n;
    }
}
