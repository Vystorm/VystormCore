package Vystorm.vystorm_core.visual;

/** Immutable finite world-space point or vector. Thread-safe. @since 0.29.0 */
public record Point3(double x, double y, double z) {
    public Point3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Coordinates must be finite");
    }
    public Point3 add(Point3 other) { return new Point3(x + other.x, y + other.y, z + other.z); }
    public Point3 subtract(Point3 other) { return new Point3(x - other.x, y - other.y, z - other.z); }
    public Point3 scale(double factor) { return new Point3(x * factor, y * factor, z * factor); }
    public double distanceSquared(Point3 other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
