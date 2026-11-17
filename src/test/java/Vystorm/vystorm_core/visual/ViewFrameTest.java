package Vystorm.vystorm_core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ViewFrameTest {
    private static final UUID WORLD = UUID.fromString("d8cc6d22-bf18-4c3b-a1d4-f71a97079c9e");

    @Test
    void givenCardinalQuarterTurnsWhenTransformingThenNormalRotatesAndCoordinatesRoundTrip() {
        Point3[] expectedNormals = {
                new Point3(0, 0, 1), new Point3(-1, 0, 0),
                new Point3(0, 0, -1), new Point3(1, 0, 0)
        };
        for (int turns = 0; turns < 4; turns++) {
            ViewFrame frame = frame(turns, new Point3(0, 0, 0));
            assertEquals(expectedNormals[turns], frame.global(new Point3(0, 0, 1)));
            frame = frame(turns, new Point3(12.5, 70, -4));
            Point3 point = new Point3(-3.25, 6.5, 2.75);
            assertEquals(point, frame.local(frame.global(point)));
        }
    }

    @Test
    void givenDifferentFramesWhenMappingThroughAndBackThenPointReturns() {
        Point3 point = new Point3(1.25, 1.75, -2.5);
        for (int sourceTurns = 0; sourceTurns < 4; sourceTurns++) {
            for (int destinationTurns = 0; destinationTurns < 4; destinationTurns++) {
                ViewFrame source = frame(sourceTurns, new Point3(10, 20, 30));
                ViewFrame destination = frame(destinationTurns, new Point3(-8, 5, 14));
                assertEquals(point, destination.through(source, source.through(destination, point)));
            }
        }
    }

    @Test
    void givenSightLineWhenCrossingFromFrontThroughApertureThenVisibleOnlyWithinAperture() {
        ViewFrame frame = frame(0, new Point3(0, 0, 0));
        Point3 eye = new Point3(0, 1, 3);

        assertTrue(frame.visible(eye, new Point3(.5, 1, -3)));
        assertFalse(frame.visible(new Point3(0, 1, -3), new Point3(.5, 1, 3)), "back-to-front is clipped");
        assertFalse(frame.visible(eye, new Point3(.5, 1, 2)), "a point before the plane is clipped");
        assertFalse(frame.visible(eye, new Point3(6, 1, -3)), "ray misses the horizontal aperture");
        assertFalse(frame.visible(eye, new Point3(0, 10, -3)), "ray misses the vertical aperture");
    }

    @Test
    void givenBodyDimensionsWhenCrossingThenEntireBodyMustFit() {
        ViewFrame frame = frame(0, new Point3(0, 0, 0));
        assertTrue(frame.crosses(new Point3(.5, .2, 2), new Point3(.5, .2, -2), .2, 1.5));
        assertFalse(frame.crosses(new Point3(1.9, .2, 2), new Point3(1.9, .2, -2), .2, 1.5));
        assertFalse(frame.crosses(new Point3(0, 3, 2), new Point3(0, 3, -2), .1, 1.2));
        assertFalse(frame.crosses(new Point3(0, 1, -2), new Point3(0, 1, 2), .1, 1), "back-to-front is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> frame.crosses(new Point3(0, 0, 1), new Point3(0, 0, -1), Double.NaN, 1));
    }

    private static ViewFrame frame(int turns, Point3 origin) {
        return new ViewFrame(WORLD, origin, turns, 2, 4);
    }
}
