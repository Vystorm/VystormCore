package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.internal.wire.Bytes;
import Vystorm.vystorm_core.packet.internal.wire.Cells;
import Vystorm.vystorm_core.packet.internal.wire.ChunkPacket;
import Vystorm.vystorm_core.packet.internal.wire.SectionCodec;
import Vystorm.vystorm_core.packet.internal.wire.SectionIndex;
import Vystorm.vystorm_core.packet.internal.wire.WireTables;
import Vystorm.vystorm_core.packet.view.ChunkEdit;
import Vystorm.vystorm_core.packet.view.SectionEdit;
import java.util.UUID;

/**
 * One chunk packet on its way to one viewer, shared by every layer: parsed once, each section decoded at most once (on
 * first access), encoded once at the end. Sections no layer wrote to keep their bytes; written sections are encoded in
 * their smallest legal form ({@link SectionCodec#writeBlocks}). While a MASK layer runs ({@link #mask(boolean)}) every
 * written cell counts as hidden: the block entity of a hidden cell is removed and its light is sent as 0 (only the
 * light arrays of sections with hidden cells are touched). For one MASK layer that writes exactly its hidden cells this
 * is byte for byte what VystormAntiXray's own {@code ChunkPacket.rewrite} sends. Netty thread, one instance per packet.
 *
 * <p>Internal: not part of the public API.
 */
public final class ChunkEditImpl implements ChunkEdit {
    private final ChunkPacket p;
    private final byte[] whole;
    private final UUID world;
    private final int minY, sections;
    private final WireTables t;
    private SectionIndex index;
    private final Section[] secs;
    private boolean maskMode = true;
    private long written;
    /** Counters of the last {@link #encode}. */
    public int sectionsRewritten, cellsHidden, blockEntitiesRemoved, lightArraysZeroed;

    public ChunkEditImpl(ChunkPacket p, UUID world, int minY, int sections, WireTables t) {
        this.p = p;
        this.whole = p.b;
        this.world = world;
        this.minY = minY;
        this.sections = sections;
        this.t = t;
        this.secs = new Section[sections];
    }

    /**
     * The next layer starts: whether it is a MASK layer (its writes hide cells). Cells hidden by the layers before it are
     * locked: a later layer's writes to them are ignored, so no layer can show again what an earlier mask hid.
     */
    public void mask(boolean mask) {
        this.maskMode = mask;
        for (Section s : secs) if (s != null && s.hidden != null) s.locked = s.hidden.clone();
    }

    /** Writes ignored because an earlier MASK layer hid the cell (for the counters). */
    public long ignored;

    /** Cells written since the last call (for the per-layer counters). */
    public long takeWritten() { long n = written; written = 0; return n; }

    @Override public UUID world() { return world; }

    @Override public int chunkX() { return p.x; }

    @Override public int chunkZ() { return p.z; }

    @Override public int minY() { return minY; }

    @Override public int sections() { return sections; }

    @Override public int blockEntities() { return p.blockEntities.size(); }

    @Override public int[] heightmapTops() { return p.tops(minY, sections * 16); }

    private SectionIndex index() {
        if (index == null) index = p.index(sections);
        return index;
    }

    private int sectionOf(int y) {
        int s = (y - minY) >> 4;
        if (y < minY || s >= sections) throw new IndexOutOfBoundsException("y " + y + " is outside the chunk (" + minY + " to " + (minY + sections * 16 - 1) + ")");
        return s;
    }

    @Override public int state(int x, int y, int z) { return section(sectionOf(y)).get(Cells.index(x, y, z)); }

    @Override public int realState(int x, int y, int z) { return section(sectionOf(y)).real(Cells.index(x, y, z)); }

    @Override public void set(int x, int y, int z, int wireId) { section(sectionOf(y)).set(Cells.index(x, y, z), wireId); }

    @Override public Section section(int i) {
        if (i < 0 || i >= sections) throw new IndexOutOfBoundsException("section " + i + " of " + sections);
        Section s = secs[i];
        if (s == null) {
            index();
            secs[i] = s = new Section(i);
        }
        return s;
    }

    /** One section: shown states (materialised on first write), the original states, the hidden cells. */
    public final class Section implements SectionEdit {
        private final int i;
        /** Shown states once a layer wrote (null before; then the real states are shown). */
        private int[] shown;
        /** Shown state of every cell after a fill of all cells without decode (-1: none). */
        private int uniformShown = -1;
        /** True when {@link #shown} is the index's decoded array (the real states must be decoded again). */
        private boolean realTaken;
        private int[] realCopy;
        /** Cells written by MASK layers (null: none). */
        long[] hidden;
        /** Cells hidden by the layers before the one that runs now (null: none). */
        long[] locked;
        boolean read, writtenTo;

        Section(int i) { this.i = i; }

        @Override public int index() { return i; }

        private int[] realIds() {
            if (!realTaken) return ChunkEditImpl.this.index.ids(i);
            if (realCopy == null) {
                realCopy = new int[4096];
                int u = ChunkEditImpl.this.index.uniform(i);
                if (u >= 0) java.util.Arrays.fill(realCopy, u);
                else SectionCodec.readBlocks(new Bytes.Reader(whole, blocksStart(), ChunkEditImpl.this.index.biomesStart(i)), realCopy);
            }
            return realCopy;
        }

        private int blocksStart() { return ChunkEditImpl.this.index.start(i) + 4; }

        @Override public int uniform() { return ChunkEditImpl.this.index.uniform(i); }

        @Override public int get(int k) {
            read = true;
            if (shown != null) return shown[k];
            if (uniformShown >= 0) return uniformShown;
            int u = ChunkEditImpl.this.index.uniform(i);
            return u >= 0 ? u : realIds()[k];
        }

        @Override public int real(int k) {
            read = true;
            int u = ChunkEditImpl.this.index.uniform(i);
            return u >= 0 ? u : realIds()[k];
        }

        @Override public void copyTo(int[] out) {
            read = true;
            if (shown != null) System.arraycopy(shown, 0, out, 0, 4096);
            else if (uniformShown >= 0) java.util.Arrays.fill(out, 0, 4096, uniformShown);
            else {
                int u = ChunkEditImpl.this.index.uniform(i);
                if (u >= 0) java.util.Arrays.fill(out, 0, 4096, u);
                else System.arraycopy(realIds(), 0, out, 0, 4096);
            }
        }

        @Override public void copyRealTo(int[] out) {
            read = true;
            int u = ChunkEditImpl.this.index.uniform(i);
            if (u >= 0) java.util.Arrays.fill(out, 0, 4096, u);
            else System.arraycopy(realIds(), 0, out, 0, 4096);
        }

        private void materialize() {
            if (shown != null) return;
            if (uniformShown >= 0) { shown = new int[4096]; java.util.Arrays.fill(shown, uniformShown); uniformShown = -1; return; }
            int u = ChunkEditImpl.this.index.uniform(i);
            if (u >= 0) { shown = new int[4096]; java.util.Arrays.fill(shown, u); return; }
            shown = ChunkEditImpl.this.index.ids(i); // the decode is taken over, the real states are read again if asked
            realTaken = true;
        }

        private void mark(int k) {
            writtenTo = true;
            written++;
            if (!maskMode) return;
            if (hidden == null) hidden = new long[Cells.WORDS];
            hidden[k >>> 6] |= 1L << (k & 63);
        }

        private void check(int wireId) { if (wireId < 0 || wireId >= t.size()) throw new IllegalArgumentException("wire id " + wireId + " of " + t.size()); }

        private boolean lockedAt(int k) { return locked != null && (locked[k >>> 6] & (1L << (k & 63))) != 0; }

        @Override public void set(int k, int wireId) {
            check(wireId);
            if (lockedAt(k)) { ignored++; return; }
            materialize();
            shown[k] = wireId;
            mark(k);
        }

        @Override public void fill(long[] cells, int wireId) {
            check(wireId);
            if (cells.length < Cells.WORDS) throw new IllegalArgumentException("cells needs 64 longs");
            if (Cells.isAll(cells) && (locked == null || Cells.isNone(locked))) {
                shown = null;
                uniformShown = wireId;
                writtenTo = true;
                written += 4096;
                if (maskMode) hidden = Cells.ALL.clone();
                return;
            }
            materialize();
            for (int wi = 0; wi < Cells.WORDS; wi++) {
                long w = cells[wi];
                while (w != 0) {
                    int k = wi << 6 | Long.numberOfTrailingZeros(w);
                    w &= w - 1;
                    if (lockedAt(k)) { ignored++; continue; }
                    shown[k] = wireId;
                    mark(k);
                }
            }
        }

        @Override public void remap(int[] map) {
            if (map.length != t.size()) throw new IllegalArgumentException("map needs " + t.size() + " entries");
            materialize();
            for (int k = 0; k < 4096; k++) {
                int n = map[shown[k]];
                if (n == shown[k]) continue;
                if (lockedAt(k)) { ignored++; continue; }
                check(n);
                shown[k] = n;
                mark(k);
            }
        }

        @Override public boolean untouched() { return !read && !writtenTo; }

        boolean hiddenAt(int k) { return hidden != null && (hidden[k >>> 6] & (1L << (k & 63))) != 0; }
    }

    /**
     * The packet as the layers left it (packet id first): the same array when nothing changed. Throws
     * {@link Bytes.Malformed} when the section buffer does not hold exactly this chunk's sections.
     */
    public byte[] encode() {
        SectionIndex ix = index(); // the shape is always checked: a chunk of another height is never sent as it is
        if (ix.trailing() != 0) throw new Bytes.Malformed(ix.trailing() + " bytes after " + sections + " sections (another world height?)");
        boolean any = false;
        for (Section s : secs) if (s != null && s.writtenTo) { any = true; break; }
        sectionsRewritten = cellsHidden = blockEntitiesRemoved = lightArraysZeroed = 0;
        if (!any) return whole;
        Bytes.Writer sw = new Bytes.Writer(p.bufferEnd - p.bufferStart + 64);
        Bytes.Writer bw = new Bytes.Writer(2200);
        for (int i = 0; i < sections; i++) {
            Section s = secs[i];
            if (s == null || !s.writtenTo) { sw.bytes(whole, ix.start(i), ix.end(i) - ix.start(i)); continue; }
            sectionsRewritten++;
            if (s.hidden != null) cellsHidden += Cells.count(s.hidden);
            if (s.shown == null) {
                int f = s.uniformShown;
                sw.s16(t.air(f) ? 0 : 4096);
                sw.s16(t.fluid(f) ? 4096 : 0);
                sw.u8(0);
                sw.varint(f);
            } else {
                bw.p = 0;
                int[] counts = SectionCodec.writeBlocks(bw, s.shown, t);
                sw.s16(counts[0]);
                sw.s16(counts[1]);
                sw.bytes(bw.b, 0, bw.p);
            }
            sw.bytes(whole, ix.biomesStart(i), ix.end(i) - ix.biomesStart(i));
        }
        Bytes.Writer w = new Bytes.Writer(whole.length + 64);
        w.bytes(whole, 0, p.bufferLenPos);
        w.varint(sw.p);
        w.bytes(sw.b, 0, sw.p);
        int keep = 0;
        boolean[] drop = new boolean[p.blockEntities.size()];
        for (int i = 0; i < drop.length; i++) {
            ChunkPacket.BlockEntity be = p.blockEntities.get(i);
            int s = (be.y() - minY) >> 4;
            drop[i] = be.y() >= minY && s < sections && secs[s] != null && secs[s].hiddenAt(Cells.index(be.x(), be.y(), be.z()));
            if (!drop[i]) keep++;
        }
        blockEntitiesRemoved = drop.length - keep;
        w.varint(keep);
        for (int i = 0; i < drop.length; i++) {
            if (drop[i]) continue;
            ChunkPacket.BlockEntity be = p.blockEntities.get(i);
            w.bytes(whole, be.start(), be.end() - be.start());
        }
        int lightOut = w.p;
        w.bytes(whole, p.light.start, p.light.end - p.light.start);
        long[][] shownMasks = shownMasks();
        lightArraysZeroed = p.light.zero(w.b, lightOut, shownMasks);
        return w.done();
    }

    /** Per section the cells NOT hidden (null where nothing is hidden), as the light rule wants them. */
    long[][] shownMasks() {
        long[][] out = new long[sections][];
        for (int i = 0; i < sections; i++) {
            Section s = secs[i];
            if (s == null || s.hidden == null) continue;
            long[] m = new long[Cells.WORDS];
            for (int wi = 0; wi < Cells.WORDS; wi++) m[wi] = ~s.hidden[wi];
            out[i] = m;
        }
        return out;
    }
}
