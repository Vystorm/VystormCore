package Vystorm.vystorm_core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HorizontalFrameTest {
    private static final UUID WORLD = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");

    @Test
    void givenSegmentCrossingPlaneFromAboveWhenInsideRectangleThenReturnsIntersection() {
        HorizontalFrame frame = new HorizontalFrame(WORLD, new Point3(10, 5, -4), 2, 3);

        Optional<Point3> intersection = frame.intersection(new Point3(10, 9, -4), new Point3(12, 1, 0));

        assertEquals(Optional.of(new Point3(11, 5, -2)), intersection);
    }

    @Test
    void givenCrossingOnApertureEdgeWhenIntersectingThenIncludesBoundary() {
        HorizontalFrame frame = new HorizontalFrame(WORLD, new Point3(0, 10, 0), 2, 3);

        assertEquals(Optional.of(new Point3(2, 10, 3)),
                frame.intersection(new Point3(2, 12, 3), new Point3(2, 8, 3)));
    }

    @Test
    void givenCrossingOutsideEitherHorizontalExtentWhenIntersectingThenClipsIt() {
        HorizontalFrame frame = new HorizontalFrame(WORLD, new Point3(0, 10, 0), 2, 3);

        assertTrue(frame.intersection(new Point3(0, 12, 0), new Point3(4.1, 8, 0)).isEmpty());
        assertTrue(frame.intersection(new Point3(0, 12, 0), new Point3(0, 8, 6.1)).isEmpty());
    }

    @Test
    void givenSegmentNotPassingFromAboveToBelowWhenIntersectingThenRejectsIt() {
        HorizontalFrame frame = new HorizontalFrame(WORLD, new Point3(0, 10, 0), 2, 3);

        assertTrue(frame.intersection(new Point3(0, 12, 0), new Point3(0, 10, 0)).isEmpty(),
                "ending on the plane does not cross it");
        assertTrue(frame.intersection(new Point3(0, 10, 0), new Point3(0, 8, 0)).isEmpty(),
                "starting on the plane does not cross from above");
        assertTrue(frame.intersection(new Point3(0, 12, 0), new Point3(0, 11, 0)).isEmpty());
        assertTrue(frame.intersection(new Point3(0, 8, 0), new Point3(0, 12, 0)).isEmpty(),
                "below-to-above is clipped");
        assertTrue(frame.intersection(new Point3(0, 10.0000005, 0), new Point3(0, 8, 0)).isEmpty(),
                "starts within the plane epsilon");
    }

    @Test
    void givenDifferentHorizontalFramesWhenTranslatingThenPreservesRelativeCoordinates() {
        HorizontalFrame source = new HorizontalFrame(WORLD, new Point3(15.5, 70, -8.5), 4, 5);
        HorizontalFrame destination = new HorizontalFrame(UUID.randomUUID(), new Point3(-30.25, 42, 19.75), 2, 3);
        Point3 sourcePoint = new Point3(17.25, 68.5, -11.0);

        assertEquals(new Point3(-28.5, 40.5, 17.25), source.through(destination, sourcePoint));
    }

    @Test
    void givenInvalidApertureDimensionsWhenCreatingFrameThenRejectsThem() {
        assertThrows(IllegalArgumentException.class, () -> frame(0, 1));
        assertThrows(IllegalArgumentException.class, () -> frame(1, 0));
        assertThrows(IllegalArgumentException.class, () -> frame(-0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> frame(1, -0.1));
        assertThrows(IllegalArgumentException.class, () -> frame(16.01, 1));
        assertThrows(IllegalArgumentException.class, () -> frame(1, 16.01));
        assertThrows(IllegalArgumentException.class, () -> frame(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> frame(1, Double.POSITIVE_INFINITY));
        assertEquals(16, frame(16, 16).halfWidth());
    }

    private static HorizontalFrame frame(double halfWidth, double halfDepth) {
        return new HorizontalFrame(WORLD, new Point3(0, 64, 0), halfWidth, halfDepth);
    }
}
