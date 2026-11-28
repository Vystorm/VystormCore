package Vystorm.vystorm_core.packet.internal.wire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The light part of chunk and light update packets: four bitsets (sky, block, empty sky, empty block; bit i is light
 * section i, which is world section i - 1) and then the sky arrays and the block arrays (2048 bytes each, one per set
 * bit, nibble k = cell k, low nibble first).
 */
public final class LightData {
    /** One light array in the buffer: its section (world section index, may be -1 or sections) and offset of the data. */
    public record Array(boolean sky, int section, int offset) {}

    public final int start, end;
    public final List<Array> arrays;

    private LightData(int start, int end, List<Array> arrays) { this.start = start; this.end = end; this.arrays = arrays; }

    /**
     * @param bitsetBytes how the bitsets are written: 26.2 as a varint count of longs, 26.3 as a varint count of bytes
     *                    ({@code BitSet.toByteArray}); the startup self-test finds out which one this server uses
     */
    public static LightData parse(Bytes.Reader r, boolean bitsetBytes) {
        int start = r.p;
        long[] sky = bitset(r, bitsetBytes), block = bitset(r, bitsetBytes);
        bitset(r, bitsetBytes);
        bitset(r, bitsetBytes);
        List<Array> arrays = new ArrayList<>();
        list(r, sky, true, arrays);
        list(r, block, false, arrays);
        return new LightData(start, r.p, arrays);
    }

    private static long[] bitset(Bytes.Reader r, boolean bytes) {
        if (bytes) {
            int n = r.count(512);
            long[] w = new long[(n + 7) / 8];
            for (int i = 0; i < n; i++) w[i >> 3] |= (long) r.u8() << ((i & 7) * 8);
            return w;
        }
        int n = r.count(64);
        long[] w = new long[n];
        for (int i = 0; i < n; i++) w[i] = r.s64();
        return w;
    }

    private static void list(Bytes.Reader r, long[] mask, boolean sky, List<Array> out) {
        int n = r.count(4096), bit = -1;
        for (int i = 0; i < n; i++) {
            bit = nextBit(mask, bit + 1);
            int len = r.count(1 << 16);
            if (len != 2048) throw new Bytes.Malformed("light array of " + len + " bytes");
            if (bit < 0) throw new Bytes.Malformed("more light arrays than mask bits");
            out.add(new Array(sky, bit - 1, r.p));
            r.skip(len);
        }
    }

    static int nextBit(long[] words, int from) {
        for (int i = from; i < words.length * 64; i++) if ((words[i >>> 6] & (1L << (i & 63))) != 0) return i;
        return -1;
    }

    /**
     * Zeroes the hidden cells of every array in {@code out}, where this light data was copied to offset
     * {@code outStart}. Returns how many arrays changed. Sections outside the mask range stay as they are.
     */
    public int zero(byte[] out, int outStart, long[][] shown) {
        int changed = 0;
        for (Array a : arrays) {
            if (a.section < 0 || a.section >= shown.length) continue;
            long[] m = shown[a.section];
            if (m == null || Cells.isAll(m)) continue;
            if (zeroHidden(out, outStart + (a.offset - start), m)) changed++;
        }
        return changed;
    }

    /** Zeroes the nibbles of hidden cells in place; true if anything was set. */
    public static boolean zeroHidden(byte[] data, int off, long[] shown) {
        if (Cells.isNone(shown)) { Arrays.fill(data, off, off + 2048, (byte) 0); return true; }
        boolean any = false;
        for (int wi = 0; wi < 64; wi++) {
            long w = shown[wi];
            if (w == -1L) continue;
            int base = off + wi * 32;
            if (w == 0) { Arrays.fill(data, base, base + 32, (byte) 0); any = true; continue; }
            for (int b = 0; b < 64; b++) {
                if ((w & (1L << b)) != 0) continue;
                int idx = base + (b >> 1);
                data[idx] = (byte) ((b & 1) == 0 ? data[idx] & 0xF0 : data[idx] & 0x0F);
            }
            any = true;
        }
        return any;
    }

    public static int nibble(byte[] data, int off, int k) { int v = data[off + (k >> 1)]; return (k & 1) == 0 ? v & 15 : (v >> 4) & 15; }
}
