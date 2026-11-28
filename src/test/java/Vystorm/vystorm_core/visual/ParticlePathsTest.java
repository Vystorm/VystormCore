package Vystorm.vystorm_core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticlePathsTest {
    @Test
    void givenLineWhenBuildingThenReturnsBoundedImmutableInterpolatedPoints() {
        var points = ParticlePaths.line(new Point3(0, 2, 4), new Point3(10, 6, 0), 512);
        assertEquals(512, points.size());
        assertEquals(new Point3(0, 2, 4), points.getFirst());
        assertEquals(new Point3(10, 6, 0), points.getLast());
        assertTrue(points.get(255).x() < points.get(256).x());
        assertThrows(UnsupportedOperationException.class, () -> points.add(new Point3(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.line(new Point3(0, 0, 0), new Point3(1, 0, 0), 513));
    }

    @Test
    void givenFrameWhenBuildingOutlineThenReturnsFourBoundedEdges() {
        ViewFrame frame = new ViewFrame(UUID.randomUUID(), new Point3(1, 2, 3), 1, 3, 5);
        var points = ParticlePaths.outline(frame, 128);
        assertEquals(512, points.size());
        assertEquals(new Point3(1, 2, 0), points.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> points.clear());
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.outline(frame, 129));
    }

    @Test
    void givenInvalidCoordinatesWhenConstructingOrCombiningThenRejectsNonFiniteGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new Point3(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Point3(Double.MAX_VALUE, 0, 0).add(new Point3(Double.MAX_VALUE, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ParticlePaths.line(new Point3(-Double.MAX_VALUE, 0, 0), new Point3(Double.MAX_VALUE, 0, 0), 2));
        assertThrows(IllegalArgumentException.class,
                () -> new ViewFrame(UUID.randomUUID(), new Point3(0, 0, 0), 0, Double.NaN, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BlockPosition.containing(new Point3(Double.NaN, 0, 0)));
    }

    @Test
    void givenTwoCornersInAnyOrder_whenBuildingBox_thenTwelveEdgesThroughAllEightCorners() {
        var points = ParticlePaths.box(new Point3(2, 3, 4), new Point3(0, 1, 1), 5);
        assertEquals(60, points.size());
        for (double x : new double[] {0, 2}) for (double y : new double[] {1, 3}) for (double z : new double[] {1, 4})
            assertTrue(points.contains(new Point3(x, y, z)), "corner " + x + " " + y + " " + z);
        for (Point3 p : points) {
            assertTrue(p.x() >= 0 && p.x() <= 2 && p.y() >= 1 && p.y() <= 3 && p.z() >= 1 && p.z() <= 4);
            int onFace = (p.x() == 0 || p.x() == 2 ? 1 : 0) + (p.y() == 1 || p.y() == 3 ? 1 : 0) + (p.z() == 1 || p.z() == 4 ? 1 : 0);
            assertTrue(onFace >= 2, "point on an edge: " + p);
        }
        assertThrows(UnsupportedOperationException.class, () -> points.clear());
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.box(new Point3(0, 0, 0), new Point3(1, 1, 1), 1));
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.box(new Point3(0, 0, 0), new Point3(1, 1, 1), 129));
        assertEquals(points, ParticlePathsInterface.DEFAULT.box(new Point3(2, 3, 4), new Point3(0, 1, 1), 5));
    }

    @Test
    void givenRadius_whenBuildingCircle_thenEvenPointsAtThatDistanceOnOneHeight() {
        Point3 centre = new Point3(10, 64, -5);
        var points = ParticlePaths.circle(centre, 3, 24);
        assertEquals(24, points.size());
        for (Point3 p : points) {
            assertEquals(64, p.y());
            assertEquals(9, Math.pow(p.x() - 10, 2) + Math.pow(p.z() + 5, 2), 1e-9);
        }
        assertEquals(new Point3(13, 64, -5), points.getFirst());
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.circle(centre, 0, 24));
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.circle(centre, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> ParticlePaths.circle(centre, 3, 513));
        assertEquals(points, ParticlePathsInterface.DEFAULT.circle(centre, 3, 24));
    }
}
