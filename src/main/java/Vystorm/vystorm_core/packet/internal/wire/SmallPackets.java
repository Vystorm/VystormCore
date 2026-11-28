package Vystorm.vystorm_core.packet.internal.wire;

import java.util.function.IntPredicate;

/** Byte-side rewrites of the light update and the section blocks update packets (bodies after the packet id). */
public final class SmallPackets {
    private SmallPackets() {}

    /** A light update packet: varint chunk x, varint chunk z, light data. */
    public record LightUpdate(int x, int z, int lightStart, LightData light) {
        public static LightUpdate parse(byte[] b, int from, boolean bitsetBytes) {
            Bytes.Reader r = new Bytes.Reader(b, from);
            int x = r.varint(), z = r.varint();
            int start = r.p;
            LightData light = LightData.parse(r, bitsetBytes);
            if (r.p != b.length) throw new Bytes.Malformed((b.length - r.p) + " bytes after a light update");
            return new LightUpdate(x, z, start, light);
        }

        /** A copy with hidden cells at light 0; the same array when nothing changes. */
        public byte[] zero(byte[] b, long[][] shown) {
            byte[] out = b.clone();
            return light.zero(out, lightStart, shown) == 0 ? b : out;
        }
    }

    /**
     * A section blocks update: long section position (x 22 bits, z 22 bits, y 20 bits), varint count, then per entry a
     * varlong {@code state << 12 | x << 8 | z << 4 | y}.
     */
    public record SectionUpdate(int sx, int sy, int sz, long[] entries, int from) {
        public static SectionUpdate parse(byte[] b, int from) {
            Bytes.Reader r = new Bytes.Reader(b, from);
            long pos = r.s64();
            int n = r.count(4096);
            long[] e = new long[n];
            for (int i = 0; i < n; i++) e[i] = r.varlong();
            if (r.p != b.length) throw new Bytes.Malformed((b.length - r.p) + " bytes after a section update");
            return new SectionUpdate((int) (pos >> 42), (int) (pos << 44 >> 44), (int) (pos << 22 >> 42), e, from);
        }

        /** Section-local cell index (y << 8 | z << 4 | x) of an entry. */
        public static int cell(long entry) {
            int x = (int) (entry >> 8) & 15, z = (int) (entry >> 4) & 15, y = (int) entry & 15;
            return y << 8 | z << 4 | x;
        }

        /**
         * The packet without the entries whose cell is hidden; the same array when none is, null when all are (the
         * packet is dropped).
         */
        public byte[] trim(byte[] b, IntPredicate hiddenCell) {
            int keep = 0;
            boolean[] drop = new boolean[entries.length];
            for (int i = 0; i < entries.length; i++) if (hiddenCell.test(cell(entries[i]))) drop[i] = true; else keep++;
            if (keep == entries.length) return b;
            if (keep == 0) return null;
            Bytes.Writer w = new Bytes.Writer(b.length);
            w.bytes(b, 0, from + 8);
            w.varint(keep);
            for (int i = 0; i < entries.length; i++) if (!drop[i]) w.varlong(entries[i]);
            return w.done();
        }

        public int dropped(IntPredicate hiddenCell) {
            int n = 0;
            for (long e : entries) if (hiddenCell.test(cell(e))) n++;
            return n;
        }
    }
}
