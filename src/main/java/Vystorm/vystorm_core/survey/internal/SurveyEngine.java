package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.BorderRun;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.OpenChunk;
import Vystorm.vystorm_core.survey.RoomId;
import Vystorm.vystorm_core.survey.RoomInfo;
import Vystorm.vystorm_core.survey.SurveyFace;
import Vystorm.vystorm_core.survey.SurveyRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The survey of one chunk from its {@link SurveyGrid}: sky column, halls (sliding window), reach (breadth-first
 * search), room labels (flood fill inside the chunk), room table, seeds, border runs and reach runs. The reference is
 * the study prototype {@code survey.js} (rule R2, reach N); the fixture test compares both cell for cell.
 *
 * <p>Plain Java, no Bukkit, deterministic. Worker threads only ({@link SurveyThreads#assertWorker}); one survey uses
 * scratch arrays of the calling thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyEngine {
    private static final int UNSEEN = 255;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SurveyEngine() {}

    /** Surveys the centre chunk of the grid. */
    public static ChunkSurvey survey(SurveyGrid grid, SurveyRule rule, UUID worldId, int chunkX, int chunkZ, long stamp) {
        SurveyThreads.assertWorker("SurveyEngine.survey");
        if (grid.margin < Math.min(16, rule.reach() + rule.hallRadius() + 1) && grid.margin < 16)
            throw new IllegalArgumentException("margin " + grid.margin + " too small for " + rule);
        Scratch s = SCRATCH.get();
        int w = grid.width, h = grid.height, ww = w * w, n = ww * h, m = grid.margin;
        byte[] cls = grid.cells;
        byte[] dist = s.dist(n);
        Arrays.fill(dist, 0, n, (byte) UNSEEN);
        long[] sky = s.sky(h);
        short[] skyTop = new short[256];
        short[] skyBottom = rule.voidBelow() ? new short[256] : null;
        // 1. sky column (from the top, and from the bottom with voidBelow)
        for (int z = 0; z < w; z++)
            for (int x = 0; x < w; x++) {
                int col = z * w + x;
                boolean centre = x >= m && x < m + 16 && z >= m && z < m + 16;
                int c0 = ((z - m) << 4) | (x - m);
                int y = h - 1;
                for (; y >= 0; y--) {
                    int i = y * ww + col;
                    if ((cls[i] & 3) == 0) break;
                    dist[i] = 0;
                    if (centre) mark(sky, (y << 8) | c0);
                }
                if (centre) skyTop[c0] = (short) (y + 1);
                if (rule.voidBelow()) {
                    int b = 0;
                    for (; b < h; b++) {
                        int i = b * ww + col;
                        if ((cls[i] & 3) == 0) break;
                        dist[i] = 0;
                        if (centre) mark(sky, (b << 8) | c0);
                    }
                    if (centre) skyBottom[c0] = (short) b;
                }
            }
        // 2. halls: open cells whose (2r+1)^3 box is fully open count like sky (separable erosion x, z, y)
        if (rule.hallRadius() > 0) {
            byte[] a = s.a(n), b = s.b(n);
            for (int i = 0; i < n; i++) a[i] = (byte) ((cls[i] & 3) != 0 ? 1 : 0);
            int r = rule.hallRadius();
            for (int y = 0; y < h; y++) for (int z = 0; z < w; z++) erode(a, b, (y * w + z) * w, 1, w, r);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) erode(b, a, y * ww + x, w, w, r);
            for (int z = 0; z < w; z++) for (int x = 0; x < w; x++) erode(a, b, z * w + x, ww, h, r);
            for (int i = 0; i < n; i++) if (b[i] != 0) dist[i] = 0;
        }
        // 3. reach: breadth-first from every sky (and hall) cell through open cells, at most rule.reach() steps
        int reach = rule.reach();
        boolean doors = rule.doorsStopReach();
        if (reach > 0) {
            int[] q = s.queue(n);
            int head = 0, tail = 0;
            for (int i = 0; i < n; i++) {
                if (dist[i] != 0 || (doors && (cls[i] & CellClass.DOOR) != 0)) continue;
                int x = i % w, rest = i / w, z = rest % w, y = rest / w;
                if ((x > 0 && fresh(cls, dist, i - 1)) || (x < w - 1 && fresh(cls, dist, i + 1))
                        || (z > 0 && fresh(cls, dist, i - w)) || (z < w - 1 && fresh(cls, dist, i + w))
                        || (y > 0 && fresh(cls, dist, i - ww)) || (y < h - 1 && fresh(cls, dist, i + ww)))
                    q[tail++] = i;
            }
            while (head < tail) {
                int i = q[head++];
                int d = dist[i] & 0xFF;
                if (d >= reach || (doors && (cls[i] & CellClass.DOOR) != 0)) continue;
                byte next = (byte) (d + 1);
                int x = i % w, rest = i / w, z = rest % w, y = rest / w;
                if (x > 0 && fresh(cls, dist, i - 1)) { dist[i - 1] = next; q[tail++] = i - 1; }
                if (x < w - 1 && fresh(cls, dist, i + 1)) { dist[i + 1] = next; q[tail++] = i + 1; }
                if (z > 0 && fresh(cls, dist, i - w)) { dist[i - w] = next; q[tail++] = i - w; }
                if (z < w - 1 && fresh(cls, dist, i + w)) { dist[i + w] = next; q[tail++] = i + w; }
                if (y > 0 && fresh(cls, dist, i - ww)) { dist[i - ww] = next; q[tail++] = i - ww; }
                if (y < h - 1 && fresh(cls, dist, i + ww)) { dist[i + ww] = next; q[tail++] = i + ww; }
            }
        }
        // 4. rooms: connected enclosed cells inside the chunk, ids in scan order (y, z, x)
        int cn = 256 * h;
        char[] lab = s.labels(cn);
        Arrays.fill(lab, 0, cn, (char) 0);
        int[] stack = s.stack(cn);
        List<int[]> table = new ArrayList<>(); // volume, mouth, fluid, border
        int rooms = 0;
        for (int c = 0; c < cn; c++) {
            if (lab[c] != 0) continue;
            int g = grid(c, m, w, ww);
            if (!enclosed(cls, dist, g, reach)) continue;
            if (rooms == 65535) break;
            char id = (char) ++rooms;
            int volume = 0;
            boolean mouth = false, fluid = false, border = false;
            int sp = 0;
            stack[sp++] = c;
            lab[c] = id;
            while (sp > 0) {
                int cc = stack[--sp];
                volume++;
                int x = cc & 15, z = (cc >>> 4) & 15, y = cc >>> 8;
                int gg = (y * w + z + m) * w + x + m;
                if ((cls[gg] & 3) == CellClass.FLUID) fluid = true;
                if (x == 0 || x == 15 || z == 0 || z == 15) border = true;
                if (!mouth) mouth = surface(cls, dist, gg - 1, reach) || surface(cls, dist, gg + 1, reach)
                        || surface(cls, dist, gg - w, reach) || surface(cls, dist, gg + w, reach)
                        || (y > 0 && surface(cls, dist, gg - ww, reach)) || (y < h - 1 && surface(cls, dist, gg + ww, reach));
                if (x > 0 && lab[cc - 1] == 0 && enclosed(cls, dist, gg - 1, reach)) { lab[cc - 1] = id; stack[sp++] = cc - 1; }
                if (x < 15 && lab[cc + 1] == 0 && enclosed(cls, dist, gg + 1, reach)) { lab[cc + 1] = id; stack[sp++] = cc + 1; }
                if (z > 0 && lab[cc - 16] == 0 && enclosed(cls, dist, gg - w, reach)) { lab[cc - 16] = id; stack[sp++] = cc - 16; }
                if (z < 15 && lab[cc + 16] == 0 && enclosed(cls, dist, gg + w, reach)) { lab[cc + 16] = id; stack[sp++] = cc + 16; }
                if (y > 0 && lab[cc - 256] == 0 && enclosed(cls, dist, gg - ww, reach)) { lab[cc - 256] = id; stack[sp++] = cc - 256; }
                if (y < h - 1 && lab[cc + 256] == 0 && enclosed(cls, dist, gg + ww, reach)) { lab[cc + 256] = id; stack[sp++] = cc + 256; }
            }
            table.add(new int[] {volume, mouth ? 1 : 0, fluid ? 1 : 0, border ? 1 : 0});
        }
        List<RoomInfo> infos = new ArrayList<>(rooms);
        for (int r = 0; r < rooms; r++) {
            int[] t = table.get(r);
            boolean mouth = t[1] != 0;
            infos.add(new RoomInfo(new RoomId(worldId, chunkX, chunkZ, r + 1), t[0], mouth, !mouth && t[3] == 0, t[2] != 0));
        }
        // 5. seeds: first cell of each room per section
        int sections = h >> 4;
        int[][] seeds = new int[sections][];
        int[] seen = s.seen(rooms + 1);
        Arrays.fill(seen, 0, rooms + 1, -1);
        int[] buf = new int[64];
        for (int sec = 0; sec < sections; sec++) {
            int len = 0;
            for (int k = 0, base = sec << 12; k < 4096; k++) {
                int l = lab[base | k];
                if (l == 0 || seen[l] == sec) continue;
                seen[l] = sec;
                if (len + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[len++] = l;
                buf[len++] = k;
            }
            seeds[sec] = Arrays.copyOf(buf, len);
        }
        // 6. border runs per side face
        BorderRun[][] borders = new BorderRun[4][];
        for (SurveyFace face : SurveyFace.values()) borders[face.ordinal()] = runs(lab, face, h);
        // 7. reach runs: surface cells outside the sky column (reach and hall cells)
        int[] reachBuf = new int[64];
        int rl = 0, start = -1;
        for (int c = 0; c <= cn; c++) {
            boolean in = c < cn && lab[c] == 0 && !isSet(sky, c) && open(cls, grid(c, m, w, ww)) && !enclosed(cls, dist, grid(c, m, w, ww), reach);
            if (in && start < 0) start = c;
            else if (!in && start >= 0) {
                if (rl + 2 > reachBuf.length) reachBuf = Arrays.copyOf(reachBuf, reachBuf.length * 2);
                reachBuf[rl++] = start;
                reachBuf[rl++] = c - start;
                start = -1;
            }
        }
        // 8. open cells of the chunk
        long[][] words = new long[sections][OpenChunk.WORDS];
        for (int c = 0; c < cn; c++) if (open(cls, grid(c, m, w, ww))) words[c >>> 12][(c & 4095) >>> 6] |= 1L << (c & 63);
        OpenChunk cells = OpenChunk.adopt(grid.minY >> 4, words);
        return ChunkSurvey.of(worldId, chunkX, chunkZ, grid.minY, h, ChunkSurvey.FORMAT, rule.hash(), stamp, cells.hash(),
                rule.voidBelow(), infos, seeds, borders, Arrays.copyOf(reachBuf, rl), skyTop, skyBottom, cells);
    }

    private static BorderRun[] runs(char[] lab, SurveyFace face, int h) {
        List<BorderRun> out = new ArrayList<>();
        int last = 0, runStart = 0, pos = 0;
        for (int y = 0; y < h; y++)
            for (int t = 0; t < 16; t++, pos++) {
                int x = switch (face) { case WEST -> 0; case EAST -> 15; default -> t; };
                int z = switch (face) { case NORTH -> 0; case SOUTH -> 15; default -> t; };
                int l = lab[(y << 8) | (z << 4) | x];
                if (l != last) {
                    if (last > 0) out.add(new BorderRun(runStart, pos - runStart, last));
                    last = l;
                    runStart = pos;
                }
            }
        if (last > 0) out.add(new BorderRun(runStart, pos - runStart, last));
        return out.toArray(new BorderRun[0]);
    }

    /** One line of the erosion: dst = 1 where the window of +-r along the line is fully open inside the grid. */
    private static void erode(byte[] src, byte[] dst, int base, int stride, int len, int r) {
        int p = 0;
        while (p < len) {
            if (src[base + p * stride] == 0) {
                dst[base + p * stride] = 0;
                p++;
                continue;
            }
            int a = p;
            while (p < len && src[base + p * stride] != 0) p++;
            int b = p - 1; // run [a, b]
            for (int q = a; q <= b; q++) dst[base + q * stride] = (byte) (q - r >= a && q + r <= b ? 1 : 0);
        }
    }

    private static int grid(int c, int m, int w, int ww) {
        return (c >>> 8) * ww + (((c >>> 4) & 15) + m) * w + (c & 15) + m;
    }

    private static boolean open(byte[] cls, int g) { return (cls[g] & 3) != 0; }

    private static boolean fresh(byte[] cls, byte[] dist, int j) { return (dist[j] & 0xFF) == UNSEEN && (cls[j] & 3) != 0; }

    private static boolean enclosed(byte[] cls, byte[] dist, int g, int reach) {
        return (cls[g] & 3) != 0 && (dist[g] & 0xFF) > reach;
    }

    private static boolean surface(byte[] cls, byte[] dist, int g, int reach) {
        return (cls[g] & 3) != 0 && (dist[g] & 0xFF) <= reach;
    }

    private static void mark(long[] bits, int c) { bits[c >>> 6] |= 1L << (c & 63); }

    private static boolean isSet(long[] bits, int c) { return (bits[c >>> 6] & (1L << (c & 63))) != 0; }

    /** Reusable arrays of one worker thread. */
    private static final class Scratch {
        private byte[] dist = new byte[0], a = new byte[0], b = new byte[0];
        private int[] queue = new int[0], stack = new int[0], seen = new int[0];
        private char[] labels = new char[0];
        private long[] sky = new long[0];

        byte[] dist(int n) { return dist.length >= n ? dist : (dist = new byte[n]); }

        byte[] a(int n) { return a.length >= n ? a : (a = new byte[n]); }

        byte[] b(int n) { return b.length >= n ? b : (b = new byte[n]); }

        int[] queue(int n) { return queue.length >= n ? queue : (queue = new int[n]); }

        int[] stack(int n) { return stack.length >= n ? stack : (stack = new int[n]); }

        int[] seen(int n) { return seen.length >= n ? seen : (seen = new int[n]); }

        char[] labels(int n) { return labels.length >= n ? labels : (labels = new char[n]); }

        long[] sky(int h) {
            int n = (h * 256 + 63) >>> 6;
            if (sky.length < n) sky = new long[n];
            else Arrays.fill(sky, 0, n, 0L);
            return sky;
        }
    }
}
