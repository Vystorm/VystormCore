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
}
