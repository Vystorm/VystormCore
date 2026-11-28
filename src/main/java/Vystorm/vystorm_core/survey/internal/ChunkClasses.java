package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.OpenChunk;
import java.util.Arrays;

/**
 * The cell classes of one chunk, one byte per cell, section by section; a uniform section (all stone, all air) keeps
 * one value instead of 4096 bytes. Index inside a section: {@code (y & 15) << 8 | z << 4 | x}. Immutable once built.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ChunkClasses {
    private final int minY;
    private final byte[][] sections;
    private final byte[] uniform;

    private ChunkClasses(int minY, byte[][] sections, byte[] uniform) {
        this.minY = minY;
        this.sections = sections;
        this.uniform = uniform;
    }

    /** From a full array, index {@code ((y - minY) * 16 + z) * 16 + x}. */
    public static ChunkClasses of(int minY, int height, byte[] cells) {
        if (cells.length != height * 256) throw new IllegalArgumentException("cells " + cells.length);
        Builder b = new Builder(minY, height);
        for (int s = 0; s < height >> 4; s++) b.section(s, Arrays.copyOfRange(cells, s << 12, (s + 1) << 12));
        return b.build();
    }

    public int minY() { return minY; }

    public int height() { return sections.length << 4; }

    public int sectionCount() { return sections.length; }

    /** Class of a cell; y relative to {@link #minY()} (0 = bottom). */
    public byte at(int x, int yIndex, int z) {
        byte[] s = sections[yIndex >> 4];
        return s == null ? uniform[yIndex >> 4] : s[(yIndex & 15) << 8 | z << 4 | x];
    }

    /** The section's bytes, or null if the section is uniform ({@link #uniform(int)}). */
    byte[] section(int index) { return sections[index]; }

    /** The value of a uniform section. */
    byte uniform(int index) { return uniform[index]; }

    /** Copies one row of 16 x cells (y relative to minY) into {@code out} from {@code fromX} to {@code toX} (exclusive). */
    void row(int yIndex, int z, int fromX, int toX, byte[] out, int at) {
        byte[] s = sections[yIndex >> 4];
        if (s == null) Arrays.fill(out, at, at + toX - fromX, uniform[yIndex >> 4]);
        else System.arraycopy(s, (yIndex & 15) << 8 | z << 4 | fromX, out, at, toX - fromX);
    }

    /** The open cells of this chunk. */
    public OpenChunk open() {
        long[][] words = new long[sections.length][];
        for (int s = 0; s < sections.length; s++) {
            long[] w = new long[OpenChunk.WORDS];
            byte[] bytes = sections[s];
            if (bytes == null) {
                if (CellClass.open(uniform[s])) Arrays.fill(w, -1L);
            } else {
                for (int k = 0; k < 4096; k++) if ((bytes[k] & 3) != 0) w[k >>> 6] |= 1L << (k & 63);
            }
            words[s] = w;
        }
        return OpenChunk.adopt(minY >> 4, words);
    }

    /** Same chunk with one cell changed (copy of the affected section). */
    public ChunkClasses with(int x, int yIndex, int z, byte value) {
        int s = yIndex >> 4;
        if (at(x, yIndex, z) == value) return this;
        byte[][] next = sections.clone();
        byte[] u = uniform.clone();
        byte[] bytes = sections[s] == null ? filled(uniform[s]) : sections[s].clone();
        bytes[(yIndex & 15) << 8 | z << 4 | x] = value;
        next[s] = bytes;
        return new ChunkClasses(minY, next, u);
    }

    /** A changeable copy of one section's 4096 bytes (also of a uniform section). */
    byte[] copySection(int index) {
        return sections[index] == null ? filled(uniform[index]) : sections[index].clone();
    }

    /** Same chunk with whole sections replaced: {@code replaced[s]} when not null (the arrays are taken, not copied). */
    ChunkClasses withSections(byte[][] replaced) {
        byte[][] next = sections.clone();
        for (int s = 0; s < replaced.length && s < next.length; s++) if (replaced[s] != null) next[s] = replaced[s];
        return new ChunkClasses(minY, next, uniform.clone());
    }

    private static byte[] filled(byte value) {
        byte[] b = new byte[4096];
        Arrays.fill(b, value);
        return b;
    }

    /** Rough memory of the classes in bytes. */
    public int footprint() {
        int n = 64 + sections.length * 8;
        for (byte[] s : sections) if (s != null) n += 4096 + 16;
        return n;
    }

    /** Builds a chunk section by section. */
    public static final class Builder {
        private final int minY;
        private final byte[][] sections;
        private final byte[] uniform;

        public Builder(int minY, int height) {
            if (height <= 0 || (height & 15) != 0 || (minY & 15) != 0) throw new IllegalArgumentException("height " + height);
            this.minY = minY;
            this.sections = new byte[height >> 4][];
            this.uniform = new byte[height >> 4];
        }

        /** A uniform section. */
        public Builder uniform(int index, byte value) {
            sections[index] = null;
            uniform[index] = value;
            return this;
        }

        /** A section from 4096 bytes (taken without copying; stored as uniform if all equal). */
        public Builder section(int index, byte[] bytes) {
            if (bytes.length != 4096) throw new IllegalArgumentException("a section has 4096 cells");
            byte first = bytes[0];
            boolean same = true;
            for (int k = 1; k < 4096 && same; k++) same = bytes[k] == first;
            if (same) return uniform(index, first);
            sections[index] = bytes;
            return this;
        }

        public ChunkClasses build() { return new ChunkClasses(minY, sections.clone(), uniform.clone()); }
    }
}
