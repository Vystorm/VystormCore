package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.WorldPick;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.ParticlePaths;
import Vystorm.vystorm_core.visual.Point3;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockFace;

/**
 * Particle points of the world pick preview (design 4.5): the 12 edges of a block or container, the square of a face
 * drawn slightly in front of it, an entity's box, the top of the feet block with a short line in the facing direction,
 * plus the plugin's own boxes. Points farther than the preview distance from the eye are dropped; the plugin's boxes
 * get fewer points per block when the pass would be too large, and the list is cut at the limit. Pure. Core-internal.
 */
public final class PickOutline {
    /** Points per edge of the target's own outline. */
    static final int TARGET_EDGE = 5;
    /** A small gap so the lines sit outside the block and do not flicker inside its faces. */
    static final double GAP = 0.02;

    private PickOutline() {}

    public static List<Point3> points(PickKind kind, WorldTargets.Resolution r, List<WorldPick.Box> extra, Point3 eye, int maxDistance, int maxPoints) {
        List<Point3> target = new ArrayList<>(target(kind, r));
        double limit = (double) maxDistance * maxDistance;
        target.removeIf(p -> p.distanceSquared(eye) > limit);
        List<Point3> out = new ArrayList<>(Math.min(maxPoints, 512));
        for (Point3 p : target) { if (out.size() >= maxPoints) break; out.add(p); }
        if (extra == null || extra.isEmpty() || out.size() >= maxPoints) return List.copyOf(out);
        int room = maxPoints - out.size();
        List<Point3> boxes = List.of();
        for (double perBlock = 2; perBlock >= 0.0625; perBlock /= 2) {
            boxes = boxes(extra, perBlock, eye, limit);
            if (boxes.size() <= room) break;
        }
        for (Point3 p : boxes) { if (out.size() >= maxPoints) break; out.add(p); }
        return List.copyOf(out);
    }

    /** The outline of the aimed target alone (no distance clip). */
    static List<Point3> target(PickKind kind, WorldTargets.Resolution r) {
        if (kind == PickKind.POSITION && r.target() instanceof Picked.Position p) return position(p);
        return switch (r.hit()) {
            case WorldTargets.Hit.OnBlock b -> kind == PickKind.FACE ? face(b.block(), b.face()) : block(b.block());
            case WorldTargets.Hit.OnEntity e -> ParticlePaths.box(e.boxMin().add(new Point3(-GAP, -GAP, -GAP)), e.boxMax().add(new Point3(GAP, GAP, GAP)), TARGET_EDGE);
            case WorldTargets.Hit.None n -> List.of();
        };
    }

    static List<Point3> block(BlockPosition b) {
        return ParticlePaths.box(new Point3(b.x() - GAP, b.y() - GAP, b.z() - GAP), new Point3(b.x() + 1 + GAP, b.y() + 1 + GAP, b.z() + 1 + GAP), TARGET_EDGE);
    }

    /** The face square, 0.03 in front of the face, plus its centre point. */
    static List<Point3> face(BlockPosition b, BlockFace face) {
        double off = 0.03;
        double x0 = b.x(), y0 = b.y(), z0 = b.z(), x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        Point3[] c = switch (face) {
            case UP -> sq(new Point3(x0, y1 + off, z0), new Point3(x1, y1 + off, z0), new Point3(x1, y1 + off, z1), new Point3(x0, y1 + off, z1));
            case DOWN -> sq(new Point3(x0, y0 - off, z0), new Point3(x1, y0 - off, z0), new Point3(x1, y0 - off, z1), new Point3(x0, y0 - off, z1));
            case NORTH -> sq(new Point3(x0, y0, z0 - off), new Point3(x1, y0, z0 - off), new Point3(x1, y1, z0 - off), new Point3(x0, y1, z0 - off));
            case SOUTH -> sq(new Point3(x0, y0, z1 + off), new Point3(x1, y0, z1 + off), new Point3(x1, y1, z1 + off), new Point3(x0, y1, z1 + off));
            case WEST -> sq(new Point3(x0 - off, y0, z0), new Point3(x0 - off, y0, z1), new Point3(x0 - off, y1, z1), new Point3(x0 - off, y1, z0));
            case EAST -> sq(new Point3(x1 + off, y0, z0), new Point3(x1 + off, y0, z1), new Point3(x1 + off, y1, z1), new Point3(x1 + off, y1, z0));
            default -> null;
        };
        if (c == null) return block(b);
        List<Point3> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.addAll(ParticlePaths.line(c[i], c[(i + 1) % 4], 7));
        out.add(c[0].add(c[2]).scale(0.5));
        return out;
    }

    private static Point3[] sq(Point3 a, Point3 b, Point3 c, Point3 d) { return new Point3[] {a, b, c, d}; }

    /** Top square of the block at the feet and a line of 1.5 blocks from its centre in the facing direction. */
    static List<Point3> position(Picked.Position p) {
        BlockPosition cell = p.cell();
        double y = p.at().y() + 0.05;
        Point3[] c = sq(new Point3(cell.x(), y, cell.z()), new Point3(cell.x() + 1, y, cell.z()), new Point3(cell.x() + 1, y, cell.z() + 1), new Point3(cell.x(), y, cell.z() + 1));
        List<Point3> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.addAll(ParticlePaths.line(c[i], c[(i + 1) % 4], 6));
        Point3 centre = new Point3(cell.x() + .5, y, cell.z() + .5);
        Point3 tip = centre.add(new Point3(p.facing().getModX() * 1.5, 0, p.facing().getModZ() * 1.5));
        out.addAll(ParticlePaths.line(centre, tip, 7));
        return out;
    }

    private static List<Point3> boxes(List<WorldPick.Box> extra, double perBlock, Point3 eye, double limit) {
        List<Point3> out = new ArrayList<>();
        for (WorldPick.Box box : extra) {
            if (box == null) continue;
            double longest = Math.max(box.max().x() - box.min().x(), Math.max(box.max().y() - box.min().y(), box.max().z() - box.min().z()));
            int n = (int) Math.max(2, Math.min(128, Math.ceil(longest * perBlock) + 1));
            for (Point3 p : ParticlePaths.box(box.min(), box.max(), n)) if (p.distanceSquared(eye) <= limit) out.add(p);
        }
        return out;
    }
}
