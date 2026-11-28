package Vystorm.vystorm_core.survey;

import java.util.Arrays;

/**
 * Bitsets of one chunk survey for masking the chunk without its neighbours, one {@code long[64]} per 16x16x16 section
 * (bit {@code k = (y & 15) << 8 | (z & 15) << 4 | (x & 15)} is word {@code k >>> 6}, bit {@code k & 63}):
 *
 * <ul>
 *   <li>{@code surface}: surface cells (sky column and reach cells);</li>
 *   <li>{@code visible}: surface cells and their six neighbours inside the chunk, i.e. what a player on the surface
 *   can see (the ground, the walls of a cave mouth); everything else is {@code hidden} and can be sent as stone;</li>
 *   <li>{@code enclosed}: open cells that are not surface; only when the survey's cells were known
 *   ({@link #hasEnclosed()}).</li>
 * </ul>
 *
 * Built from the stored record alone (reach runs and sky columns), so it is ready the moment a chunk loads.
 * Immutable and lock-free to read on any thread; uniform sections share one array.
 *
 * @since 0.68.0
 */
public final class SurveyMask {
    private static final long[] NONE = new long[OpenChunk.WORDS];
    private static final long[] ALL = filled();

    private final int minSection;
    private final long[][] surface;
    private final long[][] visible;
    private final long[][] enclosed;

    private SurveyMask(int minSection, long[][] surface, long[][] visible, long[][] enclosed) {
        this.minSection = minSection;
        this.surface = surface;
        this.visible = visible;
        this.enclosed = enclosed;
    }

    private static long[] filled() {
        long[] a = new long[OpenChunk.WORDS];
        Arrays.fill(a, -1L);
        return a;
    }

    private static long[] share(long[] w) {
        boolean none = true, all = true;
        for (long v : w) {
            if (v != 0) none = false;
            if (v != -1L) all = false;
        }
        return none ? NONE : all ? ALL : w;
    }

    static SurveyMask build(ChunkSurvey s) {
        int sections = s.height() >> 4, n = s.height() * 256;
        short[][] sky = s.sky();
        short[] top = sky[0], bottom = sky[1];
        long[] surf = new long[(n + 63) >>> 6];
        // sky column: layers above the highest sky top are all open, only the layers between need the columns
        int minTop = s.height(), maxTop = 0, maxBottom = 0;
        for (int col = 0; col < 256; col++) {
            minTop = Math.min(minTop, top[col]);
            maxTop = Math.max(maxTop, top[col]);
            if (bottom != null) maxBottom = Math.max(maxBottom, bottom[col]);
        }
        Arrays.fill(surf, maxTop << 2, s.height() << 2, -1L);
        for (int col = 0; col < 256; col++) {
            for (int yi = top[col]; yi < maxTop; yi++) { int c = yi << 8 | col; surf[c >>> 6] |= 1L << (c & 63); }
            if (bottom != null) for (int yi = 0; yi < bottom[col]; yi++) { int c = yi << 8 | col; surf[c >>> 6] |= 1L << (c & 63); }
        }
        int[] runs = s.reachRunsArray();
        for (int i = 0; i + 1 < runs.length; i += 2)
            for (int c = runs[i], e = Math.min(n, c + runs[i + 1]); c < e; c++) surf[c >>> 6] |= 1L << (c & 63);
        long[] vis = dilate(surf);
        OpenChunk cells = s.cellsOrNull();
        long[][] es = null;
        if (cells != null) {
            es = new long[sections][];
            int base = s.minY() >> 4;
            for (int sec = 0; sec < sections; sec++) {
                long[] w = new long[OpenChunk.WORDS];
                for (int i = 0; i < OpenChunk.WORDS; i++) w[i] = cells.word(base + sec, i) & ~surf[(sec << 6) + i];
                es[sec] = share(w);
            }
        }
        long[][] ss = new long[sections][], vs = new long[sections][];
        for (int sec = 0; sec < sections; sec++) {
            ss[sec] = share(Arrays.copyOfRange(surf, sec << 6, (sec + 1) << 6));
            vs[sec] = share(Arrays.copyOfRange(vis, sec << 6, (sec + 1) << 6));
        }
        return new SurveyMask(s.minY() >> 4, ss, vs, es);
    }

    /** Lanes of 16 x cells in a word: the x = 0 and the x = 15 bit of each lane. */
    private static final long LANE_LOW = 0x0001_0001_0001_0001L, LANE_HIGH = 0x8000_8000_8000_8000L;

    /**
     * Six-neighbour dilation inside the chunk, word by word: a word holds four rows of 16 x cells (z, z+1, z+2, z+3
     * of one layer), a layer is 4 words, so x moves by one bit inside a lane, z by 16 bits (across the words of the
     * layer), y by 4 words.
     */
    static long[] dilate(long[] in) {
        int words = in.length;
        long[] out = in.clone();
        for (int i = 0; i < words; i++) {
            long w = in[i];
            if (w == 0) continue;
            out[i] |= ((w << 1) & ~LANE_LOW) | ((w >>> 1) & ~LANE_HIGH) | (w << 16) | (w >>> 16);
            int inLayer = i & 3;
            if (inLayer < 3) out[i + 1] |= w >>> 48; // z + 1 of the last row lands in the next word's first row
            if (inLayer > 0) out[i - 1] |= w << 48;  // z - 1 of the first row lands in the previous word's last row
            if (i >= 4) out[i - 4] |= w;
            if (i + 4 < words) out[i + 4] |= w;
        }
        return out;
    }

    public int minSection() { return minSection; }

    public int sectionCount() { return surface.length; }

    /** Whether {@link #isEnclosed} and {@link #enclosedWord} are known (the survey's cells were attached). */
    public boolean hasEnclosed() { return enclosed != null; }

    private static boolean bit(long[][] sets, int min, int x, int y, int z) {
        int s = (y >> 4) - min;
        if (s < 0 || s >= sets.length) return false;
        int k = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (sets[s][k >>> 6] & (1L << (k & 63))) != 0;
    }

    public boolean isSurface(int x, int y, int z) { return bit(surface, minSection, x, y, z); }

    public boolean isVisible(int x, int y, int z) { return bit(visible, minSection, x, y, z); }

    /** Not visible from the surface inside this chunk: the cells an anti-xray plugin sends as stone. */
    public boolean isHidden(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        return s >= 0 && s < visible.length && !isVisible(x, y, z);
    }

    /** Whether the cell is enclosed; false when the cells are not known. */
    public boolean isEnclosed(int x, int y, int z) { return enclosed != null && bit(enclosed, minSection, x, y, z); }

    public long surfaceWord(int sectionY, int index) { return surface[sectionY - minSection][index]; }

    public long visibleWord(int sectionY, int index) { return visible[sectionY - minSection][index]; }

    public long enclosedWord(int sectionY, int index) {
        if (enclosed == null) throw new IllegalStateException("cells not known");
        return enclosed[sectionY - minSection][index];
    }

    /** No cell of the section is visible (a solid or cave section that can be sent as stone in one piece). */
    public boolean allHidden(int sectionY) { return visible[sectionY - minSection] == NONE; }

    /** Every cell of the section is surface (open sky). */
    public boolean allSurface(int sectionY) { return surface[sectionY - minSection] == ALL; }

    /** Rough memory of this mask in bytes (uniform sections are shared and count nothing). */
    public int footprint() {
        int n = 64;
        for (long[][] sets : new long[][][] {surface, visible, enclosed}) {
            if (sets == null) continue;
            n += 16 + sets.length * 8;
            for (long[] w : sets) if (w != NONE && w != ALL) n += 16 + OpenChunk.WORDS * 8;
        }
        return n;
    }
}
