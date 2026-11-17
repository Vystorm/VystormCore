package Vystorm.vystorm_core.display.internal;

import java.util.Arrays;

/**
 * Pure helpers for a wall of 128x128 map tiles stored as one row-major colour array ({@code stride = width * 128}).
 * Rectangles are {@code int[4]} = {x0, y0, x1, y1} in tile pixels, x1/y1 exclusive.
 */
public final class MapTiles {
    public static final int SIZE = 128;

    private MapTiles() {}

    /** Smallest rectangle of tile ({@code tx}, {@code ty}) that differs between {@code a} and {@code b}; null if equal. */
    public static int[] changed(byte[] a, byte[] b, int stride, int tx, int ty) {
        int x0 = SIZE, y0 = SIZE, x1 = -1, y1 = -1;
        int left = tx * SIZE;
        for (int y = 0; y < SIZE; y++) {
            int row = (ty * SIZE + y) * stride + left;
            int first = Arrays.mismatch(a, row, row + SIZE, b, row, row + SIZE);
            if (first < 0) continue;
            int last = SIZE - 1;
            while (last > first && a[row + last] == b[row + last]) last--;
            if (first < x0) x0 = first;
            if (last > x1) x1 = last;
            if (y < y0) y0 = y;
            y1 = y;
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1 + 1, y1 + 1};
    }

    /** Union of two rectangles (either may be null). */
    public static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    public static int[] full() { return new int[]{0, 0, SIZE, SIZE}; }

    /** Copies a rectangle of tile ({@code tx}, {@code ty}) into a new row-major array of {@code w*h} bytes. */
    public static byte[] region(byte[] pixels, int stride, int tx, int ty, int[] rect) {
        int w = rect[2] - rect[0], h = rect[3] - rect[1];
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(pixels, (ty * SIZE + rect[1] + y) * stride + tx * SIZE + rect[0], out, y * w, w);
        }
        return out;
    }
}
