package Vystorm.vystorm_core.screen.internal;

import java.util.Optional;

/**
 * View geometry of Vystorm Screens (since 0.57.0), pure and Bukkit-free. Yaw and pitch follow Bukkit: yaw 0 looks
 * along +Z and grows when turning right, pitch +90 looks straight down. View space is x right, y up, -z forward.
 *
 * <p>PINNED: the pointer is a world-fixed glyph at the anchor point {@code A = d * direction(anchor)} whose view-space
 * position the shader mirrors to {@code (-x, -y)}; {@link #pinnedPoint} returns the same point on the pinned panel.
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
        Vec c = toView(yaw, pitch, direction(anchorYaw, anchorPitch).scale(d));
        double depth = -c.z();
        if (depth <= 0.01) return Optional.empty();
        double u = -c.x() * d / depth, v = -c.y() * d / depth;
        if (!Double.isFinite(u) || !Double.isFinite(v)) return Optional.empty();
        return Optional.of(new double[] { u, v });
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
