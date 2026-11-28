package Vystorm.vystorm_core.packet.internal.wire;

/**
 * The sections of a chunk's section buffer, found once (offsets of each section, its block container and its biome
 * container, and the one state of single-value sections) and decoded on demand, each section at most once. One
 * instance belongs to one chunk on one thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class SectionIndex {
    private final byte[] buf;
    private final int from;
    private final int[] start, blocksStart, biomesStart, end, uniform;
    private final int[][] decoded;
    private final int trailing;

    public SectionIndex(byte[] buf, int sections) { this(buf, 0, buf.length, sections); }

    /** The sections of {@code buf[from, to)}; throws {@link Bytes.Malformed} when fewer sections are there. */
    public SectionIndex(byte[] buf, int from, int to, int sections) {
        this.buf = buf;
        this.from = from;
        start = new int[sections];
        blocksStart = new int[sections];
        biomesStart = new int[sections];
        end = new int[sections];
        uniform = new int[sections];
        decoded = new int[sections][];
        Bytes.Reader r = new Bytes.Reader(buf, from, to);
        for (int sec = 0; sec < sections; sec++) {
            start[sec] = r.p;
            r.s16();
            r.s16();
            blocksStart[sec] = r.p;
            if (r.remaining() < 1) throw new Bytes.Malformed("section " + sec + " has no block container");
            int bits = buf[r.p] & 0xFF;
            if (bits == 0) {
                Bytes.Reader v = new Bytes.Reader(buf, r.p + 1, to);
                uniform[sec] = v.varint();
            } else uniform[sec] = -1;
            SectionCodec.skip(r, 4096, 8);
            biomesStart[sec] = r.p;
            SectionCodec.skip(r, 64, 3);
            end[sec] = r.p;
        }
        trailing = to - r.p;
    }

    public int sections() { return start.length; }

    /** The one state of a section written as a single value, else -1. */
    public int uniform(int sec) { return uniform[sec]; }

    public byte[] buffer() { return buf; }

    public int start(int sec) { return start[sec]; }

    public int biomesStart(int sec) { return biomesStart[sec]; }

    public int end(int sec) { return end[sec]; }

    /** Bytes after the last section (0 for a packet of this height). */
    public int trailing() { return trailing; }

    /** Offset of the first byte after the last section. */
    public int sectionsEnd() { return start.length == 0 ? from : end[start.length - 1]; }

    /** The 4096 states of a section ({@code k = y << 8 | z << 4 | x}), decoded on first use (then cached). */
    public int[] ids(int sec) {
        int[] d = decoded[sec];
        if (d == null) {
            d = new int[4096];
            SectionCodec.readBlocks(new Bytes.Reader(buf, blocksStart[sec], biomesStart[sec]), d);
            decoded[sec] = d;
        }
        return d;
    }

    /** The decoded ids of a section if they were read already, else null. */
    public int[] decodedOrNull(int sec) { return sec < decoded.length ? decoded[sec] : null; }
}
