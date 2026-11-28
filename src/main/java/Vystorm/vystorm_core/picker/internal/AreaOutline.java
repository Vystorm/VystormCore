package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.ArrayList;
import java.util.List;

/**
 * Particle points of the area preview (design 4.5): the fixed corner's cell and the aimed cell outlined first, then the
 * eight corners and the twelve edges of the box. Edges up to 512 blocks long must not flood the client, so each edge is
 * clipped to the sphere of the preview distance around the eye and walked with a spacing that grows with the distance
 * from the eye (dense within {@value #NEAR} blocks, thinner farther out); when the pass would still be larger than the
 * cap, the spacing is doubled until it fits. Never more than {@code maxPoints}. Pure. Core-internal.
 */
public final class AreaOutline {
    /** Spacing within this distance from the eye is the base spacing; beyond it grows in step with the distance. */
    static final double NEAR = 8;
    /** Densest spacing: four points per block. */
    static final double BASE = 0.25;
    static final double GAP = PickOutline.GAP;

    private AreaOutline() {}

    /**
     * @param box     the box so far (null while only the first corner is aimed)
     * @param anchor  the corner already fixed (null without one)
     * @param aimed   the cell under the crosshair (null without one)
     */
    public static List<Point3> points(Picked.Area box, BlockPosition anchor, BlockPosition aimed, Point3 eye, int maxDistance, int maxPoints) {
        double limit = (double) maxDistance * maxDistance;
        List<Point3> out = new ArrayList<>(Math.min(maxPoints, 512));
        if (anchor != null) addWithin(out, PickOutline.block(anchor), eye, limit, maxPoints);
        if (aimed != null && !aimed.equals(anchor)) addWithin(out, PickOutline.block(aimed), eye, limit, maxPoints);
        if (box == null || out.size() >= maxPoints) return List.copyOf(out);
        Point3[] c = corners(box);
        for (Point3 p : c) if (out.size() < maxPoints && p.distanceSquared(eye) <= limit) out.add(p);
        int room = maxPoints - out.size();
        if (room <= 0) return List.copyOf(out);
        List<Point3> edges = List.of();
        for (double base = BASE; base <= 4096; base *= 2) {
            edges = edges(c, eye, maxDistance, base, room + 1);
            if (edges.size() <= room) break;
        }
        for (Point3 p : edges) { if (out.size() >= maxPoints) break; out.add(p); }
        return List.copyOf(out);
    }

    /** The box's outer corners (cells span to their far faces), slightly outside so they do not flicker in the faces. */
    static Point3[] corners(Picked.Area box) {
        double x0 = box.min().x() - GAP, y0 = box.min().y() - GAP, z0 = box.min().z() - GAP;
        double x1 = box.max().x() + 1 + GAP, y1 = box.max().y() + 1 + GAP, z1 = box.max().z() + 1 + GAP;
        return new Point3[] {
                new Point3(x0, y0, z0), new Point3(x1, y0, z0), new Point3(x1, y0, z1), new Point3(x0, y0, z1),
                new Point3(x0, y1, z0), new Point3(x1, y1, z0), new Point3(x1, y1, z1), new Point3(x0, y1, z1)};
    }

    private static final int[][] EDGES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    /** Inner points of every edge within the distance, without the corners; stops early once {@code stopAt} is reached. */
    private static List<Point3> edges(Point3[] c, Point3 eye, double maxDistance, double base, int stopAt) {
        List<Point3> out = new ArrayList<>();
        for (int[] e : EDGES) {
            walk(c[e[0]], c[e[1]], eye, maxDistance, base, out, stopAt);
            if (out.size() >= stopAt) break;
        }
        return out;
    }

    /**
     * Walks the part of the segment a..b inside the sphere around the eye: from the point nearest the eye outwards in
     * both directions, each step {@code base * max(1, distance / NEAR)} long, corners left out (they are added once).
     */
    static void walk(Point3 a, Point3 b, Point3 eye, double maxDistance, double base, List<Point3> out, int stopAt) {
        Point3 d = b.subtract(a);
        double len = Math.sqrt(d.x() * d.x() + d.y() * d.y() + d.z() * d.z());
        if (len < 1e-9) return;
        Point3 u = d.scale(1 / len);
        Point3 w = eye.subtract(a);
        double along = w.x() * u.x() + w.y() * u.y() + w.z() * u.z();           // eye's foot on the line, from a
        double perp2 = w.x() * w.x() + w.y() * w.y() + w.z() * w.z() - along * along;
        double r2 = maxDistance * maxDistance - perp2;
        if (r2 < 0) return;                                                      // the line passes outside the sphere
        double half = Math.sqrt(r2);
        double from = Math.max(0, along - half), to = Math.min(len, along + half);
        if (from >= to) return;
        double start = Math.max(from, Math.min(to, along));
        // outwards from the nearest point: first towards b, then towards a
        for (int dir = 1; dir >= -1; dir -= 2) {
            double t = dir == 1 ? start : start - step(a, u, start, eye, base);
            // the forward pass starts AT the nearest point (it may be the clipped start of the edge); the corners are skipped below
            while (dir == 1 ? t >= from && t <= to : t > from && t < to) {
                if (t > 1e-6 && t < len - 1e-6) {
                    out.add(a.add(u.scale(t)));
                    if (out.size() >= stopAt) return;
                }
                t += dir * step(a, u, t, eye, base);
            }
        }
    }

    private static double step(Point3 a, Point3 u, double t, Point3 eye, double base) {
        double dist = Math.sqrt(a.add(u.scale(t)).distanceSquared(eye));
        return base * Math.max(1, dist / NEAR);
    }

    private static void addWithin(List<Point3> out, List<Point3> pts, Point3 eye, double limit, int max) {
        for (Point3 p : pts) {
            if (out.size() >= max) return;
            if (p.distanceSquared(eye) <= limit) out.add(p);
        }
    }
}
