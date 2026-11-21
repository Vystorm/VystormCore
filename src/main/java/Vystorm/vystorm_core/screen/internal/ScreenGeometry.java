package Vystorm.vystorm_core.screen.internal;

import java.util.List;
import java.util.Optional;

/**
 * View geometry of Vystorm Screens (since 0.57.0), pure and Bukkit-free. Yaw and pitch follow Bukkit: yaw 0 looks
 * along +Z and grows when turning right, pitch +90 looks straight down. View space is x right, y up, -z forward.
 *
 * <p>PINNED: the pointer is a world-fixed glyph at the anchor point {@code A = d * direction(anchor)}. Since 0.66.1 the
 * shader draws it at {@code d * (Δyaw, -Δpitch)} in radians (head direction minus the direction of the glyph), so the
 * pointer moves on the screen in proportion to the mouse, with the same gain both ways and over the whole panel.
 * Before, it mirrored the glyph's view position to {@code (-x, -y)}: a perspective projection whose step grew with
 * {@code 1/cos²} towards the edges, and turning and nodding together bent its path. {@link #pinnedPoint} returns the
 * same point on the pinned panel, so the click lands where the pointer is drawn.
 * FLOATING: the crosshair is the cursor; {@link #floatingPoint} intersects the view ray with the screen plane.
 */
public final class ScreenGeometry {
    public record Vec(double x, double y, double z) {
        double dot(Vec o) { return x * o.x + y * o.y + z * o.z; }
        Vec scale(double s) { return new Vec(x * s, y * s, z * s); }
        Vec cross(Vec o) { return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
        Vec normalize() { double l = Math.sqrt(dot(this)); return l < 1e-12 ? this : scale(1 / l); }
    }

    private static final Vec UP = new Vec(0, 1, 0);

    private ScreenGeometry() { }

    public static Vec direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Right vector of the view; falls back to the yaw-only right vector when looking straight up or down. */
    static Vec right(float yaw, float pitch) {
        Vec r = direction(yaw, pitch).cross(UP);
        if (r.dot(r) > 1e-9) return r.normalize();
        double y = Math.toRadians(yaw);
        return new Vec(-Math.cos(y), 0, -Math.sin(y));
    }

    /** Camera-relative world vector into view space (x right, y up, -z forward). */
    public static Vec toView(float yaw, float pitch, Vec world) {
        Vec forward = direction(yaw, pitch), right = right(yaw, pitch), up = right.cross(forward);
        return new Vec(world.dot(right), world.dot(up), -world.dot(forward));
    }

    /** Panel-local {u, v} of the shader pointer (u right, v up), or empty when the anchor is behind the player. */
    public static Optional<double[]> pinnedPoint(float anchorYaw, float anchorPitch, float yaw, float pitch, double d) {
        // 0.66.1: linear in the view angle (the shader text.vsh draws the pointer with the same formula)
        double turn = Math.toRadians(wrap(yaw - anchorYaw)), nod = Math.toRadians(pitch - anchorPitch);
        if (Math.abs(turn) >= Math.PI / 2) return Optional.empty();   // turned away from the screen
        double u = d * turn, v = -d * nod;
        if (!Double.isFinite(u) || !Double.isFinite(v)) return Optional.empty();
        return Optional.of(new double[] { u, v });
    }

    // ---- draw order of PINNED text displays (0.66.1) ---------------------------------------------------------------
    // 26.2 clients draw text displays in the order they arrive. 26.3 clients sort them back to front by the distance
    // from the camera to each display's text origin: the translation of its pose after the text block is laid out,
    // which is the TOP-LEFT corner of the text block. A button's box (one stretched glyph) then has its origin at the
    // box's top-left corner, its label at the label's top-left corner, inside the box. Right of and below the screen
    // centre the label's corner lies farther from the camera than the box's, so 26.3 drew the label first and the box
    // over it. PINNED displays can be moved along the view ray without any visible change (the camera sits at the
    // displays' position and the billboard faces it): translation and scale times f project to the same pixels. Each
    // display gets the f that puts its sort origin at the distance of its stacking layer, so later layers are nearer.

    /** Sort distance of stacking layer 0 (the panel), in units of the PINNED distance. */
    static final double LAYER_FAR = 1.5;
    /** Each layer is this factor nearer than the one below it (covers the estimate of a label's width). */
    static final double LAYER_STEP = 1.06;
    /** Layers above this share its distance; 1.5 / 1.06^6 = 1.057 stays behind the pointer (distance 1). */
    static final int LAYER_TOP = 6;
    /** One text pixel in blocks at display scale 1; a text line is 10 px high minus 1 for the last line. */
    private static final double TEXT_PIXEL = 1.0 / 40;

    /** Sort distance of a stacking layer (0 = panel background) at PINNED distance {@code d}. */
    public static double layerDistance(int layer, double d) {
        return d * LAYER_FAR / Math.pow(LAYER_STEP, Math.max(0, Math.min(LAYER_TOP, layer)));
    }

    /**
     * Where a 26.3 client takes a text display's sort origin, in the display's local frame (x right, y up): the top-left
     * corner of a text block of {@code widthPx} text pixels and {@code lines} lines, laid out centred on the
     * translation {@code (tx, ty)} and scaled by {@code (sx, sy)}.
     */
    public static double[] textOrigin(double tx, double ty, double sx, double sy, double widthPx, int lines) {
        return new double[] { tx + sx * TEXT_PIXEL * (1 - widthPx / 2), ty + sy * TEXT_PIXEL * (10.0 * Math.max(1, lines) - 1) };
    }

    /**
     * Factor for translation and scale of a PINNED display whose sort origin is {@code (ox, oy)} at depth {@code z}
     * (positive, in front of the camera), so the origin lies at {@link #layerDistance} of its layer.
     */
    public static double layerScale(double ox, double oy, double z, int layer, double d) {
        double r = Math.sqrt(ox * ox + oy * oy + z * z);
        return r < 1e-9 ? 1 : layerDistance(layer, d) / r;
    }

    /**
     * Stacking layers of widgets in drawing order: a widget lies one layer above the highest layer of any earlier widget
     * whose box overlaps its own (the panel background is layer 0). Returns {box layer, content layer} per widget; the
     * content (label, icon) lies one layer above the box, or directly on what is below when there is no box.
     */
    public static int[][] layers(List<double[]> rects, List<Boolean> boxed) {
        int n = rects.size();
        int[][] out = new int[n][];
        int[] top = new int[n];
        for (int i = 0; i < n; i++) {
            double[] a = rects.get(i);
            int below = 0;
            for (int j = 0; j < i; j++) if (overlaps(a, rects.get(j))) below = Math.max(below, top[j]);
            int box = below + 1, content = boxed.get(i) ? box + 1 : box;
            out[i] = new int[] { box, content };
            top[i] = content;
        }
        return out;
    }

    /** True when two {u, v, width, height} rectangles share area (touching edges do not count). */
    static boolean overlaps(double[] a, double[] b) {
        return a[0] < b[0] + b[2] - 1e-9 && b[0] < a[0] + a[2] - 1e-9 && a[1] < b[1] + b[3] - 1e-9 && b[1] < a[1] + a[3] - 1e-9;
    }

    /** Degrees to -180..180. */
    static double wrap(double degrees) {
        double w = degrees % 360.0;
        if (w >= 180.0) w -= 360.0;
        if (w < -180.0) w += 360.0;
        return w;
    }

    /** Plane-local {u, v} where the view ray meets the floating screen plane, or empty when it points away. */
    public static Optional<double[]> floatingPoint(float anchorYaw, float anchorPitch, float yaw, float pitch, double d) {
        Vec normal = direction(anchorYaw, anchorPitch), ray = direction(yaw, pitch);
        double facing = ray.dot(normal);
        if (facing <= 1e-6) return Optional.empty();
        Vec hit = ray.scale(d / facing);
        Vec right = right(anchorYaw, anchorPitch), up = right.cross(normal);
        double u = hit.dot(right), v = hit.dot(up);
        if (!Double.isFinite(u) || !Double.isFinite(v)) return Optional.empty();
        return Optional.of(new double[] { u, v });
    }
}
