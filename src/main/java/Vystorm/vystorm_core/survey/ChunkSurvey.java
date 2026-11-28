package Vystorm.vystorm_core.survey;

import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The survey of one chunk: its rooms, a seed cell per room and section, the border runs of the four side faces and
 * the surface cells outside the sky column ("reach cells"). Immutable and safe on any thread.
 *
 * <p>This is what Core stores per chunk (format C). Cell labels are not stored: {@link #kindAt} and {@link #roomAt}
 * rebuild them on first use by a flood fill from the seeds through the chunk's {@link #cells() open cells}. A survey
 * made by Core carries its cells; one read from disk gets them when its chunk is loaded. Without cells both methods
 * throw {@link IllegalStateException}.
 *
 * <p>Coordinates: x and z may be world or chunk coordinates (only the low 4 bits count), y is the world y.
 *
 * @since 0.68.0
 */
public final class ChunkSurvey {
    /** Format of the stored record. */
    /** Format of the stored record (2 since the sky column per column is stored too). */
    public static final int FORMAT = 2;

    private final UUID worldId;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int height;
    private final int format;
    private final int ruleHash;
    private final long stamp;
    private final long openHash;
    private final boolean voidBelow;
    private final List<RoomInfo> rooms;
    private final int[][] seeds;
    private final BorderRun[][] borders;
    private final int[] reachRuns;
    private final OpenChunk cells;
    /** Per column (z * 16 + x): first y index of the top sky column, and the height of the sky column from below. */
    private final short[] skyTop;
    private final short[] skyBottom;
    private volatile SoftReference<Derived> derived;
    private volatile SoftReference<SurveyMask> mask;

    private ChunkSurvey(UUID worldId, int chunkX, int chunkZ, int minY, int height, int format, int ruleHash, long stamp,
                        long openHash, boolean voidBelow, List<RoomInfo> rooms, int[][] seeds, BorderRun[][] borders,
                        int[] reachRuns, short[] skyTop, short[] skyBottom, OpenChunk cells) {
        if (skyTop != null && skyTop.length != 256) throw new IllegalArgumentException("one sky top per column");
        if (skyBottom != null && skyBottom.length != 256) throw new IllegalArgumentException("one sky bottom per column");
        this.skyTop = skyTop;
        this.skyBottom = skyBottom;
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        if (height <= 0 || (height & 15) != 0 || (minY & 15) != 0) throw new IllegalArgumentException("height " + height + " minY " + minY);
        if (seeds.length != height >> 4) throw new IllegalArgumentException("one seed list per section");
        if (borders.length != 4) throw new IllegalArgumentException("four faces");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.format = format;
        this.ruleHash = ruleHash;
        this.stamp = stamp;
        this.openHash = openHash;
        this.voidBelow = voidBelow;
        this.rooms = List.copyOf(rooms);
        this.seeds = seeds;
        this.borders = borders;
        this.reachRuns = reachRuns;
        this.cells = cells;
    }

    /**
     * Core internal: a survey from its parts (the arrays are taken without copying and must not change).
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static ChunkSurvey of(UUID worldId, int chunkX, int chunkZ, int minY, int height, int format, int ruleHash,
                                 long stamp, long openHash, boolean voidBelow, List<RoomInfo> rooms, int[][] seeds,
                                 BorderRun[][] borders, int[] reachRuns, OpenChunk cells) {
        return new ChunkSurvey(worldId, chunkX, chunkZ, minY, height, format, ruleHash, stamp, openHash, voidBelow,
                rooms, seeds, borders, reachRuns, null, null, cells);
    }

    /**
     * Core internal: a survey from its parts with the sky column per column ({@code skyBottom} null without sky
     * from below); the arrays are taken without copying and must not change.
     *
     * <p>Internal: not part of the public API; may change or disappear in any version.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static ChunkSurvey of(UUID worldId, int chunkX, int chunkZ, int minY, int height, int format, int ruleHash,
                                 long stamp, long openHash, boolean voidBelow, List<RoomInfo> rooms, int[][] seeds,
                                 BorderRun[][] borders, int[] reachRuns, short[] skyTop, short[] skyBottom, OpenChunk cells) {
        return new ChunkSurvey(worldId, chunkX, chunkZ, minY, height, format, ruleHash, stamp, openHash, voidBelow,
                rooms, seeds, borders, reachRuns, skyTop, skyBottom, cells);
    }

    /**
     * Lowest world y of the sky column of a column (above the highest opaque block); {@code minY() + height()} when
     * the top cell is opaque. Needs no cells (stored in the record).
     */
    public int skyTop(int x, int z) { return minY + sky()[0][(z & 15) << 4 | (x & 15)]; }

    /** Height of the sky column from the bottom of the world (0 unless the rule counts the sky from below). */
    public int skyBottom(int x, int z) {
        short[] b = sky()[1];
        return b == null ? 0 : b[(z & 15) << 4 | (x & 15)];
    }

    /** The stored sky columns, or the ones derived from the cells for a record without them. */
    short[][] sky() {
        if (skyTop != null) return new short[][] {skyTop, skyBottom};
        Derived d = derived();
        return new short[][] {d.skyTop, voidBelow ? d.skyBottom : null};
    }

    /** Core internal: the stored sky tops (null for an old record). */
    short[] skyTopArray() { return skyTop; }

    short[] skyBottomArray() { return skyBottom; }

    /**
     * Per-section bitsets for masking a chunk without its neighbours: surface cells, the cells a player at the
     * surface can see inside this chunk (surface cells and their six neighbours) and, when the cells are known, the
     * enclosed cells. Built once and kept through a soft reference; immutable, safe on any thread (a Netty thread
     * included). Cells on the chunk's side faces that touch a neighbour's surface cell are only visible in the
     * neighbour's mask: combine both where it matters.
     */
    public SurveyMask mask() {
        SoftReference<SurveyMask> ref = mask;
        SurveyMask m = ref == null ? null : ref.get();
        if (m != null) return m;
        m = SurveyMask.build(this);
        mask = new SoftReference<>(m);
        return m;
    }

    /** Whether a cell is a surface cell outside the sky column (binary search in the reach runs). */
    boolean inReach(int c) {
        int[] runs = reachRuns;
        int lo = 0, hi = runs.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, start = runs[2 * mid];
            if (c < start) hi = mid - 1;
            else if (c >= start + runs[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    /** Core internal: the cells this record was made from, or null. */
    OpenChunk cellsOrNull() { return cells; }

    int[] reachRunsArray() { return reachRuns; }

    public UUID worldId() { return worldId; }

    public int chunkX() { return chunkX; }

    public int chunkZ() { return chunkZ; }

    /** Lowest block y of the world. */
    public int minY() { return minY; }

    /** Height of the world in blocks. */
    public int height() { return height; }

    /** Format version of the record. */
    public int format() { return format; }

    /** {@link SurveyRule#hash()} of the rule the chunk was surveyed with. */
    public int ruleHash() { return ruleHash; }

    /** Stamp of the survey, also kept in the chunk's persistent data; a different stamp there means the record is stale. */
    public long stamp() { return stamp; }

    /** {@link OpenChunk#hash()} of the cells at survey time. */
    public long openHash() { return openHash; }

    /** Whether the rule counted the column from the bottom of the world as sky too. */
    public boolean voidBelow() { return voidBelow; }

    /** The rooms in local id order ({@code rooms().get(i)} has local id {@code i + 1}). */
    public List<RoomInfo> rooms() { return rooms; }

    /** The room with this local id. */
    public RoomInfo room(int local) { return rooms.get(local - 1); }

    /** The id of a local room of this chunk. */
    public RoomId id(int local) {
        if (local < 1 || local > rooms.size()) throw new IllegalArgumentException("no room " + local);
        return rooms.get(local - 1).id();
    }

    /** Local ids of the rooms with cells in section {@code sectionY} ({@code blockY >> 4}), in scan order. */
    public int[] roomsInSection(int sectionY) {
        int[] list = seedList(sectionY);
        int[] out = new int[list.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = list[2 * i];
        return out;
    }

    /**
     * The first cell of a room in a section, as {@code (y & 15) << 8 | (z & 15) << 4 | (x & 15)}; -1 if the room has
     * no cell there.
     */
    public int seed(int room, int sectionY) {
        int[] list = seedList(sectionY);
        for (int i = 0; i < list.length; i += 2) if (list[i] == room) return list[i + 1];
        return -1;
    }

    private int[] seedList(int sectionY) {
        int s = sectionY - (minY >> 4);
        return s < 0 || s >= seeds.length ? new int[0] : seeds[s];
    }

    /** The border runs of a face in position order. */
    public List<BorderRun> borders(SurveyFace face) { return List.of(borders[face.ordinal()]); }

    /**
     * The surface cells outside the sky column as runs over {@code index = ((y - minY) * 16 + z) * 16 + x}: pairs of
     * start and length, in order.
     */
    public int[] reachRuns() { return reachRuns.clone(); }

    /** The open cells this survey was made from, if known. */
    public Optional<OpenChunk> cells() { return Optional.ofNullable(cells); }

    /**
     * This survey with the chunk's cells, if they are the ones it was made from ({@link OpenChunk#hash()} equals
     * {@link #openHash()}); empty means the blocks changed since and the record is stale.
     */
    public Optional<ChunkSurvey> withCells(OpenChunk chunkCells) {
        Objects.requireNonNull(chunkCells, "chunkCells");
        if (chunkCells.hash() != openHash || chunkCells.minSection() != minY >> 4 || chunkCells.sectionCount() != height >> 4)
            return Optional.empty();
        return Optional.of(new ChunkSurvey(worldId, chunkX, chunkZ, minY, height, format, ruleHash, stamp, openHash,
                voidBelow, rooms, seeds, borders, reachRuns, skyTop, skyBottom, chunkCells));
    }

    /** What the cell is; above and below the world: {@link CellKind#SURFACE}. */
    public CellKind kindAt(int x, int y, int z) {
        int yi = y - minY;
        if (yi < 0 || yi >= height) return CellKind.SURFACE;
        Derived d = derived();
        int c = (yi * 16 + (z & 15)) * 16 + (x & 15);
        if (!cells.isOpen(x, y, z)) return CellKind.SOLID;
        return d.label(c) != 0 || !d.surface(c) ? CellKind.ENCLOSED : CellKind.SURFACE;
    }

    /** Local id of the room the cell belongs to; 0 for solid and surface cells. */
    public int roomAt(int x, int y, int z) {
        int yi = y - minY;
        if (yi < 0 || yi >= height) return 0;
        return derived().label((yi * 16 + (z & 15)) * 16 + (x & 15));
    }

    /**
     * Whether the rebuilt labels match the record (every seed on an enclosed cell, each room's volume as stored).
     * False means the record does not belong to these cells; Core then surveys the chunk again.
     */
    public boolean consistent() { return derived().consistent; }

    /** The labels are rebuilt on first use and may be dropped again when memory is short (soft reference). */
    private Derived derived() {
        SoftReference<Derived> ref = derived;
        Derived d = ref == null ? null : ref.get();
        if (d != null) return d;
        if (cells == null) throw new IllegalStateException("cells of chunk " + chunkX + "," + chunkZ + " are not known (chunk not loaded)");
        synchronized (this) {
            ref = derived;
            d = ref == null ? null : ref.get();
            if (d == null) {
                d = new Derived(this);
                derived = new SoftReference<>(d);
            }
            return d;
        }
    }

    @Override
    public String toString() {
        return "ChunkSurvey[" + worldId + " " + chunkX + "," + chunkZ + " rooms=" + rooms.size() + " stamp=" + Long.toHexString(stamp) + "]";
    }

    /**
     * Labels rebuilt from seeds, reach runs and cells: one label array per section that holds rooms (bytes while the
     * chunk has fewer than 256 rooms), the sky column per column, reach cells by binary search in the runs.
     */
    private static final class Derived {
        private final ChunkSurvey s;
        final short[] skyTop = new short[256];
        final short[] skyBottom = new short[256];
        private final byte[][] small;
        private final char[][] big;
        final boolean consistent;

        Derived(ChunkSurvey s) {
            this.s = s;
            if (s.skyTop != null) {
                System.arraycopy(s.skyTop, 0, skyTop, 0, 256);
                if (s.skyBottom != null) System.arraycopy(s.skyBottom, 0, skyBottom, 0, 256);
            }
            int sections = s.height >> 4;
            boolean wide = s.rooms.size() > 255;
            small = wide ? null : new byte[sections][];
            big = wide ? new char[sections][] : null;
            OpenChunk cells = s.cells;
            for (int col = 0; col < 256 && s.skyTop == null; col++) {
                int x = col & 15, z = col >>> 4, yi = s.height - 1;
                while (yi >= 0 && cells.isOpen(x, yi + s.minY, z)) yi--;
                skyTop[col] = (short) (yi + 1);
                int b = 0;
                if (s.voidBelow) while (b < s.height && cells.isOpen(x, b + s.minY, z)) b++;
                skyBottom[col] = (short) b;
            }
            boolean ok = true;
            int[] volume = new int[s.rooms.size() + 1];
            int[] stack = null;
            for (int sec = 0; sec < s.seeds.length; sec++) {
                int[] list = s.seeds[sec];
                for (int i = 0; i + 1 < list.length; i += 2) {
                    int room = list[i], c = (sec << 12) | list[i + 1];
                    if (room < 1 || room > s.rooms.size() || !enclosed(c)) { ok = false; continue; }
                    int have = label(c);
                    if (have != 0) {
                        if (have != room) ok = false;
                        continue;
                    }
                    if (stack == null) stack = new int[s.height * 256];
                    volume[room] += fill(c, room, stack);
                }
            }
            for (int r = 1; r <= s.rooms.size() && ok; r++) if (volume[r] != s.rooms.get(r - 1).volume()) ok = false;
            consistent = ok;
        }

        int label(int c) {
            int sec = c >>> 12, k = c & 4095;
            if (small != null) return small[sec] == null ? 0 : small[sec][k] & 0xFF;
            return big[sec] == null ? 0 : big[sec][k];
        }

        private void label(int c, int room) {
            int sec = c >>> 12, k = c & 4095;
            if (small != null) {
                if (small[sec] == null) small[sec] = new byte[4096];
                small[sec][k] = (byte) room;
            } else {
                if (big[sec] == null) big[sec] = new char[4096];
                big[sec][k] = (char) room;
            }
        }

        /** Surface: sky column from the top or the bottom, or a reach cell. */
        boolean surface(int c) {
            int yi = c >>> 8, col = c & 255;
            if (yi >= skyTop[col] || yi < skyBottom[col]) return true;
            int[] runs = s.reachRuns;
            int lo = 0, hi = runs.length / 2 - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1, start = runs[2 * mid];
                if (c < start) hi = mid - 1;
                else if (c >= start + runs[2 * mid + 1]) lo = mid + 1;
                else return true;
            }
            return false;
        }

        boolean enclosed(int c) { return s.cells.isOpen(c & 15, (c >>> 8) + s.minY, (c >>> 4) & 15) && !surface(c); }

        private int fill(int start, int room, int[] stack) {
            int sp = 0, count = 0, top = s.height * 256;
            stack[sp++] = start;
            label(start, room);
            while (sp > 0) {
                int c = stack[--sp];
                count++;
                int x = c & 15, z = (c >>> 4) & 15;
                if (x > 0) sp = push(c - 1, room, stack, sp);
                if (x < 15) sp = push(c + 1, room, stack, sp);
                if (z > 0) sp = push(c - 16, room, stack, sp);
                if (z < 15) sp = push(c + 16, room, stack, sp);
                if (c >= 256) sp = push(c - 256, room, stack, sp);
                if (c + 256 < top) sp = push(c + 256, room, stack, sp);
            }
            return count;
        }

        private int push(int c, int room, int[] stack, int sp) {
            if (label(c) != 0 || !enclosed(c)) return sp;
            label(c, room);
            stack[sp] = c;
            return sp + 1;
        }
    }

    /** Equality of the stored content (not of the attached cells). */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChunkSurvey other)) return false;
        return worldId.equals(other.worldId) && chunkX == other.chunkX && chunkZ == other.chunkZ && minY == other.minY
                && height == other.height && format == other.format && ruleHash == other.ruleHash && stamp == other.stamp
                && openHash == other.openHash && voidBelow == other.voidBelow && rooms.equals(other.rooms)
                && Arrays.deepEquals(seeds, other.seeds) && Arrays.deepEquals(borders, other.borders)
                && Arrays.equals(reachRuns, other.reachRuns) && Arrays.equals(skyTop, other.skyTop)
                && Arrays.equals(skyBottom, other.skyBottom);
    }

    @Override
    public int hashCode() { return Objects.hash(worldId, chunkX, chunkZ, stamp, openHash, rooms.size()); }
}
