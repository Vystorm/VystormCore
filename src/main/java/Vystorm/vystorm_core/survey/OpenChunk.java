package Vystorm.vystorm_core.survey;

import java.util.Arrays;
import java.util.Objects;

/**
 * Which cells of one chunk are open (everything that is not opaque: air, fluids, see-through blocks), as one
 * {@code long[64]} bitset per 16x16x16 section. Bit {@code k = (y & 15) << 8 | (z & 15) << 4 | (x & 15)} of a section
 * is word {@code k >>> 6}, bit {@code k & 63}.
 *
 * <p>Immutable: Core publishes a new instance when a block changes (copy on write), so a worker thread can read one
 * snapshot as long as it likes.
 *
 * @since 0.68.0
 */
public final class OpenChunk {
    /** Words of one section. */
    public static final int WORDS = 64;
    private static final long[] NONE = new long[WORDS];
    private static final long[] ALL = filled();

    private final int minSection;
    private final long[][] sections;

    private OpenChunk(int minSection, long[][] sections) {
        this.minSection = minSection;
        this.sections = sections;
    }

    private static long[] filled() {
        long[] all = new long[WORDS];
        Arrays.fill(all, -1L);
        return all;
    }

    /**
     * A chunk from its sections (copied); {@code sections[i]} is section {@code minSection + i}, each 64 words long.
     */
    public static OpenChunk of(int minSection, long[][] sections) {
        Objects.requireNonNull(sections, "sections");
        long[][] copy = new long[sections.length][];
        for (int i = 0; i < sections.length; i++) copy[i] = share(sections[i].clone());
        return new OpenChunk(minSection, copy);
    }

    /**
     * Core internal: takes the arrays without copying (they must never change afterwards).
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static OpenChunk adopt(int minSection, long[][] sections) {
        for (int i = 0; i < sections.length; i++) sections[i] = share(sections[i]);
        return new OpenChunk(minSection, sections);
    }

    /** Uniform sections share one array. */
    private static long[] share(long[] words) {
        if (words.length != WORDS) throw new IllegalArgumentException("a section has " + WORDS + " words");
        boolean none = true, all = true;
        for (long w : words) {
            if (w != 0) none = false;
            if (w != -1L) all = false;
        }
        return none ? NONE : all ? ALL : words;
    }

    /** Section y ({@code blockY >> 4}) of the lowest section. */
    public int minSection() { return minSection; }

    /** Number of sections. */
    public int sectionCount() { return sections.length; }

    /** Whether the cell is open; x and z may be world or chunk coordinates (only the low 4 bits count). */
    public boolean isOpen(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length) return true;
        int k = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (sections[s][k >>> 6] & (1L << (k & 63))) != 0;
    }

    /** One word of section {@code sectionY} ({@code blockY >> 4}). */
    public long word(int sectionY, int index) { return sections[sectionY - minSection][index]; }

    /** A copy of the 64 words of section {@code sectionY}. */
    public long[] copySection(int sectionY) { return sections[sectionY - minSection].clone(); }

    /** Every cell of the section is open (air above the ground). */
    public boolean allOpen(int sectionY) { return sections[sectionY - minSection] == ALL; }

    /** No cell of the section is open (solid rock). */
    public boolean allSolid(int sectionY) { return sections[sectionY - minSection] == NONE; }

    /** The same chunk with one cell set open or closed (a new instance; this one is unchanged). */
    public OpenChunk with(int x, int y, int z, boolean open) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length || isOpen(x, y, z) == open) return this;
        int k = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        long[] words = sections[s].clone();
        if (open) words[k >>> 6] |= 1L << (k & 63);
        else words[k >>> 6] &= ~(1L << (k & 63));
        long[][] next = sections.clone();
        next[s] = share(words);
        return new OpenChunk(minSection, next);
    }

    /** 64-bit hash of all bits (stored in every survey record to detect changes made while Core did not watch). */
    public long hash() {
        long h = 0x9E3779B97F4A7C15L ^ minSection ^ ((long) sections.length << 32);
        for (long[] section : sections)
            for (long w : section) {
                h ^= w;
                h *= 0xBF58476D1CE4E5B9L;
                h ^= h >>> 31;
            }
        return h;
    }

    /** Number of open cells. */
    public long openCount() {
        long n = 0;
        for (long[] section : sections) for (long w : section) n += Long.bitCount(w);
        return n;
    }
}
