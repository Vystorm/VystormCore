package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.display.internal.MapTiles;
import java.util.List;

/**
 * Changed-rectangle sender of one shown map widget (0.72.0). Pure: no server, no packets.
 *
 * <p>Keeps Core's own copy of every tile as the client will have it once everything pending is sent ({@code target})
 * and, per tile, the rectangle where the client may still differ ({@code pending}). {@link #stage} diffs the newly
 * composed tiles against {@code target} with the same {@link MapTiles#changed} the map walls use and widens the
 * pending rectangle; {@link #flush} sends pending rectangles of the newest pixels until the byte budget is spent.
 * Diffing is cheap (a {@code mismatch} scan of 16 KiB per tile) and makes every caller better, so callers never pass
 * a rectangle. Row 0 (the shader header) only changes when the widget moves, which shows new frames anyway; a header
 * difference is still sent like any other change.
 */
final class ScreenMapSender {
    /** Bytes of a map patch packet beyond its pixels (ids, offsets, sizes, varints; estimate). */
    static final int PATCH_OVERHEAD = 16;

    /** Receives one rectangle of one tile: {@code w*h} map colours, row-major. */
    interface Sink { void patch(int tile, int x, int y, int w, int h, byte[] colours); }

    private final byte[][] target;
    private final int[][] pending;
    private int pendingCount, cursor;
    private long patches, pixels;

    /** {@code shown}: the full tiles the client got with the frames (Core keeps them, they are its own arrays). */
    ScreenMapSender(List<byte[]> shown) {
        target = shown.toArray(new byte[0][]);
        pending = new int[target.length][];
    }

    int tiles() { return target.length; }
    boolean pending() { return pendingCount > 0; }
    long patchesSent() { return patches; }
    long pixelsSent() { return pixels; }

    /** The tile as the client has it once everything pending is sent (for tests). */
    byte[] target(int tile) { return target[tile]; }

    /** The rectangle {x0, y0, x1, y1} still to send for a tile, or null (for tests). */
    int[] pendingRect(int tile) { return pending[tile] == null ? null : pending[tile].clone(); }

    /** New full tiles (Core's own arrays, same count): widens the pending rectangles where they differ. */
    void stage(List<byte[]> next) {
        if (next.size() != target.length) throw new IllegalArgumentException("tile count changed: " + next.size() + " != " + target.length);
        for (int i = 0; i < target.length; i++) {
            byte[] tile = next.get(i);
            int[] rect = MapTiles.changed(target[i], tile, MapTiles.SIZE, 0, 0);
            target[i] = tile;
            if (rect == null) continue;
            if (pending[i] == null) pendingCount++;
            pending[i] = MapTiles.union(pending[i], rect);
        }
    }

    /** Bytes a pending rectangle costs to send. */
    static int cost(int[] rect) { return (rect[2] - rect[0]) * (rect[3] - rect[1]) + PATCH_OVERHEAD; }

    /**
     * Sends pending rectangles, starting after the tile sent last (so a big redraw cannot starve later tiles), while
     * their cost fits {@code budget}. {@code mustSend}: send the first pending rectangle even when it is larger than
     * the budget (the viewer got nothing yet this tick), so every tick makes progress. Returns the bytes spent.
     */
    int flush(int budget, boolean mustSend, Sink sink) {
        int spent = 0;
        int n = target.length, start = cursor;
        for (int step = 0; step < n && pendingCount > 0; step++) {
            int i = (start + step) % n;
            int[] rect = pending[i];
            if (rect == null) continue;
            int c = cost(rect);
            if (spent + c > budget && !(mustSend && spent == 0)) continue;   // a smaller one later may still fit
            int w = rect[2] - rect[0], h = rect[3] - rect[1];
            sink.patch(i, rect[0], rect[1], w, h, MapTiles.region(target[i], MapTiles.SIZE, 0, 0, rect));
            pending[i] = null;
            pendingCount--;
            cursor = (i + 1) % n;
            spent += c;
            patches++;
            pixels += (long) w * h;
        }
        return spent;
    }
}
