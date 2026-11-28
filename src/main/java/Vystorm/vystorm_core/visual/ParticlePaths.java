package Vystorm.vystorm_core.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded particle geometry usable off-thread; emission itself is server-thread only. @since 0.29.0
 * <p>Preferred access: {@code api.particle().paths()}.
 */
public final class ParticlePaths {
    private ParticlePaths() {}
    public static List<Point3> line(Point3 start, Point3 end, int count) {
        if (count < 2 || count > 512) throw new IllegalArgumentException("Count must be 2..512");
        List<Point3> points = new ArrayList<>(count);
        Point3 delta = end.subtract(start);
        for (int i = 0; i < count; i++) points.add(start.add(delta.scale((double) i / (count - 1))));
        return List.copyOf(points);
    }
    public static List<Point3> outline(ViewFrame frame, int pointsPerEdge) {
        if (pointsPerEdge < 2 || pointsPerEdge > 128) throw new IllegalArgumentException("Edge count must be 2..128");
        double w = frame.halfWidth(), h = frame.height();
        List<Point3> corners = List.of(new Point3(-w, 0, 0), new Point3(w, 0, 0),
                new Point3(w, h, 0), new Point3(-w, h, 0));
        List<Point3> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) result.addAll(line(frame.global(corners.get(i)),
                frame.global(corners.get((i + 1) % 4)), pointsPerEdge));
        return List.copyOf(result);
    }

    /**
     * The 12 edges of the box between two corners (any order), {@code pointsPerEdge} points per edge (2..128, corners
     * included, so corners repeat). Up to 1,536 points: draw at most 512 per call. @since 0.71.0
     */
    public static List<Point3> box(Point3 min, Point3 max, int pointsPerEdge) {
        if (pointsPerEdge < 2 || pointsPerEdge > 128) throw new IllegalArgumentException("Edge count must be 2..128");
        double x0 = Math.min(min.x(), max.x()), x1 = Math.max(min.x(), max.x());
        double y0 = Math.min(min.y(), max.y()), y1 = Math.max(min.y(), max.y());
        double z0 = Math.min(min.z(), max.z()), z1 = Math.max(min.z(), max.z());
        Point3[] c = {
                new Point3(x0, y0, z0), new Point3(x1, y0, z0), new Point3(x1, y0, z1), new Point3(x0, y0, z1),
                new Point3(x0, y1, z0), new Point3(x1, y1, z0), new Point3(x1, y1, z1), new Point3(x0, y1, z1)};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        List<Point3> result = new ArrayList<>(12 * pointsPerEdge);
        for (int[] e : edges) result.addAll(line(c[e[0]], c[e[1]], pointsPerEdge));
        return List.copyOf(result);
    }

    /** A horizontal circle around {@code centre} (radius above 0, 3..512 points, evenly spaced). @since 0.71.0 */
    public static List<Point3> circle(Point3 centre, double radius, int count) {
        if (!(radius > 0) || !Double.isFinite(radius)) throw new IllegalArgumentException("Radius must be above 0");
        if (count < 3 || count > 512) throw new IllegalArgumentException("Count must be 3..512");
        List<Point3> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            result.add(new Point3(centre.x() + Math.cos(a) * radius, centre.y(), centre.z() + Math.sin(a) * radius));
        }
        return List.copyOf(result);
    }
}
