package Vystorm.vystorm_core.visual;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Upward-facing horizontal aperture, centred on origin. Pure geometry. @since 0.30.0 */
public record HorizontalFrame(UUID world, Point3 origin, double halfWidth, double halfDepth) {
    public HorizontalFrame {
        Objects.requireNonNull(world); Objects.requireNonNull(origin);
        if (!Double.isFinite(halfWidth) || !Double.isFinite(halfDepth)
                || halfWidth <= 0 || halfWidth > 16 || halfDepth <= 0 || halfDepth > 16)
            throw new IllegalArgumentException("Aperture dimensions out of bounds");
    }
    /** Intersection inside the rectangle, only for a segment from above to below the plane. */
    public Optional<Point3> intersection(Point3 eye, Point3 point) {
        double above = eye.y() - origin.y(), below = point.y() - origin.y();
        if (above <= 1.0e-6 || below >= 0) return Optional.empty();
        double t = above / (above - below);
        Point3 hit = eye.add(point.subtract(eye).scale(t));
        if (Math.abs(hit.x() - origin.x()) > halfWidth || Math.abs(hit.z() - origin.z()) > halfDepth)
            return Optional.empty();
        return Optional.of(hit);
    }
    /** Translate into a destination frame without rotating blocks or changing their vertical axis. */
    public Point3 through(HorizontalFrame destination, Point3 point) {
        return destination.origin.add(point.subtract(origin));
    }
}
