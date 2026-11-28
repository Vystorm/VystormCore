package Vystorm.vystorm_core.packet.internal.wire;

import java.util.ArrayList;
import java.util.List;

/**
 * An encoded "level chunk with light" packet of 26.x, parsed in place: int x, int z; heightmaps (varint count, each a
 * varint type and a varint-prefixed long array); varint length and the section buffer; block entities (varint count,
 * each: byte packed xz, short y, varint type, optional NBT); light data ({@link LightData}). {@code from} is the
 * offset after the packet id; everything before it is copied unchanged.
 */
public final class ChunkPacket {
    public record BlockEntity(int start, int end, int x, int y, int z) {}

    public final byte[] b;
    public final int from, x, z, bufferLenPos, bufferStart, bufferEnd, beCountPos, beEnd;
    public final List<long[]> heightmaps;
    public final List<BlockEntity> blockEntities;
    public final LightData light;

    private ChunkPacket(byte[] b, int from, int x, int z, List<long[]> heightmaps, int bufferLenPos, int bufferStart, int bufferEnd,
                        int beCountPos, List<BlockEntity> blockEntities, int beEnd, LightData light) {
        this.b = b; this.from = from; this.x = x; this.z = z; this.heightmaps = heightmaps; this.bufferLenPos = bufferLenPos;
        this.bufferStart = bufferStart; this.bufferEnd = bufferEnd; this.beCountPos = beCountPos; this.blockEntities = blockEntities;
        this.beEnd = beEnd; this.light = light;
    }

    /** Parses the whole body; throws {@link Bytes.Malformed} unless it ends exactly at the end of {@code b}. */
    public static ChunkPacket parse(byte[] b, int from, boolean bitsetBytes) {
        Bytes.Reader r = new Bytes.Reader(b, from);
        int x = r.s32(), z = r.s32();
        int maps = r.count(16);
        List<long[]> heightmaps = new ArrayList<>(maps);
        for (int i = 0; i < maps; i++) {
            r.varint();
            int n = r.count(1024);
            long[] w = new long[n];
            for (int j = 0; j < n; j++) w[j] = r.s64();
            heightmaps.add(w);
        }
        int bufferLenPos = r.p;
        int len = r.count(1 << 23);
        int bufferStart = r.p;
        r.skip(len);
        int bufferEnd = r.p;
        int beCountPos = r.p;
        int n = r.count(1 << 16);
        List<BlockEntity> bes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int start = r.p;
            int xz = r.u8(), y = r.s16();
            r.varint();
            Nbt.skipRoot(r);
            bes.add(new BlockEntity(start, r.p, (xz >> 4) & 15, y, xz & 15));
        }
        int beEnd = r.p;
        LightData light = LightData.parse(r, bitsetBytes);
        if (r.p != b.length) throw new Bytes.Malformed((b.length - r.p) + " bytes after the light data");
        return new ChunkPacket(b, from, x, z, heightmaps, bufferLenPos, bufferStart, bufferEnd, beCountPos, bes, beEnd, light);
    }

    public byte[] sectionBuffer() { return java.util.Arrays.copyOfRange(b, bufferStart, bufferEnd); }

    /** The sections of this packet, read in place (no copy of the buffer). */
    public SectionIndex index(int sections) { return new SectionIndex(b, bufferStart, bufferEnd, sections); }

    /**
     * Highest top per column (index z << 4 | x) over all heightmaps of the packet, as an absolute y of the first cell
     * above the column's top block; {@code minY} where the packet has none (then nothing is known: everything hidden).
     */
    public int[] tops(int minY, int height) {
        int[] tops = new int[256];
        java.util.Arrays.fill(tops, minY);
        int bits = 32 - Integer.numberOfLeadingZeros(height), vpl = 64 / bits;
        long mask = (1L << bits) - 1;
        for (long[] words : heightmaps) {
            for (int i = 0; i < 256; i++) {
                int li = i / vpl;
                if (li >= words.length) break;
                int v = (int) ((words[li] >>> ((i % vpl) * bits)) & mask);
                tops[i] = Math.max(tops[i], minY + v);
            }
        }
        return tops;
    }

    /** The result of a rewrite. */
    public static final class Rewrite {
        public byte[] bytes;
        public SectionCodec.Result sections;
        public int blockEntitiesRemoved, lightArraysZeroed;
    }

    /** True if a cell (chunk-local x, z; absolute y) is shown by the mask. */
    static boolean shown(long[][] shown, int minY, int x, int y, int z) {
        int sec = (y - minY) >> 4;
        if (y < minY || sec >= shown.length) return true;
        long[] m = shown[sec];
        return m == null || Cells.bit(m, Cells.index(x, y, z));
    }

    /** A new packet: hidden cells as {@code fill}, block entities in hidden cells removed, light of hidden cells 0. */
    public Rewrite rewrite(long[][] shown, int[] fill, WireTables t, int minY) {
        Rewrite out = new Rewrite();
        out.sections = SectionCodec.rewrite(sectionBuffer(), shown.length, shown, fill, t);
        if (out.sections.trailing != 0) throw new Bytes.Malformed(out.sections.trailing + " bytes after " + shown.length + " sections (another world height?)");
        byte[] sec = out.sections.buffer;
        Bytes.Writer w = new Bytes.Writer(b.length + 64);
        w.bytes(b, 0, bufferLenPos);
        w.varint(sec.length);
        w.bytes(sec, 0, sec.length);
        List<BlockEntity> keep = new ArrayList<>(blockEntities.size());
        for (BlockEntity be : blockEntities) if (shown(shown, minY, be.x, be.y, be.z)) keep.add(be);
        out.blockEntitiesRemoved = blockEntities.size() - keep.size();
        w.varint(keep.size());
        for (BlockEntity be : keep) w.bytes(b, be.start, be.end - be.start);
        int lightOut = w.p;
        w.bytes(b, light.start, light.end - light.start);
        out.lightArraysZeroed = light.zero(w.b, lightOut, shown);
        out.bytes = w.done();
        return out;
    }
}
