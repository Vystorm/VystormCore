package Vystorm.vystorm_core.visual;

import java.util.Objects;
import java.util.UUID;

/**
 * Upright rectangular aperture. Origin is bottom centre; positive local Z faces the viewer.
 * Quarter turns rotate the normal from +Z towards -X, -Z, +X. Pure, thread-safe geometry.
 * This type owns no portal, world, player or teleport state. @since 0.29.0
 */
public record ViewFrame(UUID world, Point3 origin, int quarterTurns, double halfWidth, double height) {
    public ViewFrame {
        Objects.requireNonNull(world); Objects.requireNonNull(origin);
        quarterTurns = Math.floorMod(quarterTurns, 4);
        if (!Double.isFinite(halfWidth) || !Double.isFinite(height)
                || halfWidth <= 0 || halfWidth > 16 || height <= 0 || height > 32)
            throw new IllegalArgumentException("Aperture dimensions out of bounds");
    }
    public Point3 local(Point3 point) { return rotate(point.subtract(origin), -quarterTurns); }
    public Point3 global(Point3 point) { return origin.add(rotate(point, quarterTurns)); }
    public Point3 directionTo(ViewFrame destination, Point3 vector) {
        Point3 local = rotate(vector, -quarterTurns);
        return rotate(new Point3(-local.x(), local.y(), -local.z()), destination.quarterTurns);
    }
    /** Map through the aperture, reversing its two horizontal axes. */
    public Point3 through(ViewFrame destination, Point3 point) {
        return destination.origin.add(directionTo(destination, point.subtract(origin)));
    }
    public boolean contains(double x, double y, double margin) {
        return Math.abs(x) <= halfWidth - margin && y >= margin && y <= height - margin;
    }
    /** Whether the eye-to-point segment crosses the aperture from front to back. */
    public boolean visible(Point3 eye, Point3 point) {
        Point3 a = local(eye), b = local(point);
        if (a.z() <= 1.0e-6 || b.z() >= 0) return false;
        double t = a.z() / (a.z() - b.z());
        return contains(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()), 0);
    }
    /** Feet segment crosses front to back with room for the supplied body dimensions. */
    public boolean crosses(Point3 from, Point3 to, double bodyRadius, double bodyHeight) {
        if (bodyRadius < 0 || bodyHeight < 0 || !Double.isFinite(bodyRadius) || !Double.isFinite(bodyHeight))
            throw new IllegalArgumentException("Invalid body dimensions");
        Point3 a = local(from), b = local(to);
        if (a.z() <= 0 || b.z() > 0) return false;
        double t = a.z() / (a.z() - b.z());
        double x = a.x() + t * (b.x() - a.x()), y = a.y() + t * (b.y() - a.y());
        return Math.abs(x) + bodyRadius <= halfWidth && y >= 0 && y + bodyHeight <= height;
    }
    private static Point3 rotate(Point3 p, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> p;
            case 1 -> new Point3(-p.z(), p.y(), p.x());
            case 2 -> new Point3(-p.x(), p.y(), -p.z());
            default -> new Point3(p.z(), p.y(), -p.x());
        };
    }
}
