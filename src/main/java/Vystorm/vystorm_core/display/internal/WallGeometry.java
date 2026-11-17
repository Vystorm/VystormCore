package Vystorm.vystorm_core.display.internal;

/**
 * Pure geometry of a vertical wall of map tiles. {@code (fx, fz)} is the unit direction the frames face (out of the wall,
 * towards the viewer). Tile (0, 0) is the top-left frame as seen from the front; tiles run right and down from the
 * anchor block, which is the block the top-left frame occupies.
 */
public final class WallGeometry {
    public static final int TILE = 128;
    /** Item frames sit this far in front of the wall surface. */
    static final double FRAME_OFFSET = 1.0 / 16;

    private WallGeometry() {}

    /** Viewer's right-hand direction when looking at a wall whose frames face {@code (fx, fz)}. */
    public static int[] right(int fx, int fz) { return new int[]{fz, -fx}; }

    /** Block of tile ({@code tx}, {@code ty}). */
    public static int[] tile(int ox, int oy, int oz, int fx, int fz, int tx, int ty) {
        int[] r = right(fx, fz);
        return new int[]{ox + r[0] * tx, oy - ty, oz + r[1] * tx};
    }

    /** Squared distance from a point to the wall's block bounding box (0 inside). */
    public static double distanceSquared(int ox, int oy, int oz, int fx, int fz, int width, int height, double x, double y, double z) {
        int[] a = tile(ox, oy, oz, fx, fz, 0, 0), b = tile(ox, oy, oz, fx, fz, width - 1, height - 1);
        double dx = axis(x, Math.min(a[0], b[0]), Math.max(a[0], b[0]) + 1);
        double dy = axis(y, Math.min(a[1], b[1]), Math.max(a[1], b[1]) + 1);
        double dz = axis(z, Math.min(a[2], b[2]), Math.max(a[2], b[2]) + 1);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axis(double v, double min, double max) { return v < min ? min - v : v > max ? v - max : 0; }

    /**
     * Wall pixel hit by a look ray, or null when the ray misses the wall or points away. Pixels are counted over the whole
     * wall ({@code width * 128} by {@code height * 128}).
     */
    public static int[] pixel(int ox, int oy, int oz, int fx, int fz, int width, int height, double[] eye, double[] direction) {
        int[] r = right(fx, fz);
        double px = ox + 0.5 - fx * (0.5 - FRAME_OFFSET), pz = oz + 0.5 - fz * (0.5 - FRAME_OFFSET);
        double denominator = direction[0] * fx + direction[2] * fz;
        if (denominator >= -1e-9) return null;
        double t = ((px - eye[0]) * fx + (pz - eye[2]) * fz) / denominator;
        if (t <= 0) return null;
        double hx = eye[0] + direction[0] * t, hy = eye[1] + direction[1] * t, hz = eye[2] + direction[2] * t;
        double cx = px - r[0] * 0.5, cy = oy + 1.0, cz = pz - r[1] * 0.5;
        double u = (hx - cx) * r[0] + (hz - cz) * r[1];
        double v = cy - hy;
        int x = (int) Math.floor(u * TILE), y = (int) Math.floor(v * TILE);
        if (x < 0 || y < 0 || x >= width * TILE || y >= height * TILE) return null;
        return new int[]{x, y};
    }
}
