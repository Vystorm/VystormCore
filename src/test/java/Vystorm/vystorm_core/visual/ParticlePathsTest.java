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
}
