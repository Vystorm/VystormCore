package Vystorm.vystorm_core.packet.internal.wire;

import java.util.Arrays;

/**
 * The chunk section wire format of Minecraft 26.x: per section a short block count, a short fluid count, the block
 * states container (bits byte; 0 = one varint value; up to 8 = varint palette; above = global ids; then the packed
 * longs WITHOUT a length prefix, values do not span longs) and the biome container (same layout, 64 entries, palette up
 * to 3 bits). Cell index {@code k = y << 8 | z << 4 | x} inside the section.
 */
public final class SectionCodec {
    private SectionCodec() {}

    /** Skips one container and returns its bits. */
    public static int skip(Bytes.Reader r, int entries, int maxIndirect) {
        int bits = r.u8();
        if (bits == 0) { r.varint(); return 0; }
        if (bits > 32) throw new Bytes.Malformed("container bits " + bits);
        if (bits <= maxIndirect) { int n = r.count(4096); for (int i = 0; i < n; i++) r.varint(); }
        int vpl = 64 / bits, longs = (entries + vpl - 1) / vpl;
        r.skip(longs * 8);
        return bits;
    }

    /** Reads a block container into out[4096] (global ids). */
    public static void readBlocks(Bytes.Reader r, int[] out) {
        int bits = r.u8();
        if (bits == 0) { Arrays.fill(out, 0, 4096, r.varint()); return; }
        if (bits > 32) throw new Bytes.Malformed("block bits " + bits);
        int[] palette = null;
        if (bits <= 8) { int n = r.count(4096); palette = new int[n]; for (int i = 0; i < n; i++) palette[i] = r.varint(); }
        int vpl = 64 / bits, longs = (4096 + vpl - 1) / vpl;
        long mask = (1L << bits) - 1;
        int base = r.p;
        r.skip(longs * 8);
        int i = 0;
        for (int li = 0; li < longs && i < 4096; li++) {
            long w = r.longAt(base + li * 8);
            for (int j = 0; j < vpl && i < 4096; j++, i++) {
                int v = (int) ((w >>> (j * bits)) & mask);
                if (palette == null) out[i] = v;
                else if (v < palette.length) out[i] = palette[v];
                else throw new Bytes.Malformed("palette index " + v + " of " + palette.length);
            }
        }
    }

    private static final ThreadLocal<int[][]> SCRATCH = new ThreadLocal<>();

    private static int[][] scratch(int size) {
        int[][] s = SCRATCH.get();
        if (s == null || s[0].length < size) {
            s = new int[][] {new int[size], new int[size], new int[1], new int[4096]};
            SCRATCH.set(s);
        }
        return s;
    }

    /** Writes a block container for ids[4096] in its smallest legal form and returns {nonAir, fluid}. */
    public static int[] writeBlocks(Bytes.Writer w, int[] ids, WireTables t) {
        int[][] s = scratch(t.air.length);
        int[] slot = s[0], stamp = s[1], palette = s[3];
        int gen = ++s[2][0];
        if (gen == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); s[2][0] = gen = 1; }
        int n = 0, nonAir = 0, fluid = 0;
        for (int i = 0; i < 4096; i++) {
            int id = ids[i];
            if (id < 0 || id >= t.air.length) throw new Bytes.Malformed("block state id " + id);
            if (!t.air[id]) nonAir++;
            if (t.fluid[id]) fluid++;
            if (stamp[id] != gen) { stamp[id] = gen; slot[id] = n; palette[n++] = id; }
        }
        if (n == 1) { w.u8(0); w.varint(palette[0]); return new int[] {nonAir, fluid}; }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
        boolean direct = bits > 8;
        if (direct) bits = t.directBits;
        w.u8(bits);
        if (!direct) { w.varint(n); for (int i = 0; i < n; i++) w.varint(palette[i]); }
        int vpl = 64 / bits, longs = (4096 + vpl - 1) / vpl, i = 0;
        for (int li = 0; li < longs; li++) {
            long word = 0;
            for (int j = 0; j < vpl && i < 4096; j++, i++) word |= (long) (direct ? ids[i] : slot[ids[i]]) << (j * bits);
            w.s64(word);
        }
        return new int[] {nonAir, fluid};
    }

    /** Counters of one rewrite. */
    public static final class Result {
        public byte[] buffer;
        public int sectionsRewritten, sectionsHidden, sectionsKept, cellsHidden, trailing;
    }

    /**
     * Rewrites a chunk's section buffer: cells whose bit in {@code shown[sec]} is 0 become {@code fill[sec]}. A section
     * whose mask is {@link Cells#ALL} (or null) keeps its bytes; {@link Cells#NONE} writes a one-value container.
     * Bytes after the last section are kept.
     */
    public static Result rewrite(byte[] buf, int sections, long[][] shown, int[] fill, WireTables t) {
        Result res = new Result();
        Bytes.Reader r = new Bytes.Reader(buf, 0);
        Bytes.Writer w = new Bytes.Writer(buf.length);
        int[] ids = new int[4096];
        for (int sec = 0; sec < sections; sec++) {
            int start = r.p;
            r.s16();
            r.s16();
            int blocksStart = r.p;
            skip(r, 4096, 8);
            int biomesStart = r.p;
            skip(r, 64, 3);
            int end = r.p;
            long[] m = shown == null || sec >= shown.length ? null : shown[sec];
            if (m == null || Cells.isAll(m)) { w.bytes(buf, start, end - start); res.sectionsKept++; continue; }
            int f = fill[sec];
            if (Cells.isNone(m)) {
                w.s16(t.air[f] ? 0 : 4096);
                w.s16(t.fluid[f] ? 4096 : 0);
                w.u8(0);
                w.varint(f);
                w.bytes(buf, biomesStart, end - biomesStart);
                res.sectionsHidden++;
                res.cellsHidden += 4096;
                continue;
            }
            readBlocks(new Bytes.Reader(buf, blocksStart, biomesStart), ids);
            int hidden = 0;
            for (int k = 0; k < 4096; k++) if (!Cells.bit(m, k)) { hidden++; ids[k] = f; }
            Bytes.Writer sw = new Bytes.Writer(2200);
            int[] counts = writeBlocks(sw, ids, t);
            w.s16(counts[0]);
            w.s16(counts[1]);
            w.bytes(sw.b, 0, sw.p);
            w.bytes(buf, biomesStart, end - biomesStart);
            res.sectionsRewritten++;
            res.cellsHidden += hidden;
        }
        res.trailing = buf.length - r.p;
        if (r.p < buf.length) w.bytes(buf, r.p, buf.length - r.p);
        res.buffer = w.done();
        return res;
    }

    /** Decoded view of a buffer (for checks): ids per section and the counts written in the headers. */
    public static final class Decoded {
        public int[][] ids;
        public int[] blockCount, fluidCount;
        public int trailing;
    }

    public static Decoded decode(byte[] buf, int sections) {
        Decoded d = new Decoded();
        d.ids = new int[sections][4096];
        d.blockCount = new int[sections];
        d.fluidCount = new int[sections];
        Bytes.Reader r = new Bytes.Reader(buf, 0);
        for (int sec = 0; sec < sections; sec++) {
            d.blockCount[sec] = r.s16();
            d.fluidCount[sec] = r.s16();
            readBlocks(r, d.ids[sec]);
            skip(r, 64, 3);
        }
        d.trailing = buf.length - r.p;
        return d;
    }

    /** Writes sections from ids (test and self-test helper): biomes as one value 0. */
    public static byte[] encode(int[][] ids, WireTables t) {
        Bytes.Writer w = new Bytes.Writer(4096);
        for (int[] section : ids) {
            Bytes.Writer sw = new Bytes.Writer(2200);
            int[] counts = writeBlocks(sw, section, t);
            w.s16(counts[0]);
            w.s16(counts[1]);
            w.bytes(sw.b, 0, sw.p);
            w.u8(0);
            w.varint(0);
        }
        return w.done();
    }
}
