package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScreenGeometryTest {
    @Test void givenSameDirection_whenPinned_thenPointerAtCentre() {
        double[] p = ScreenGeometry.pinnedPoint(30f, 10f, 30f, 10f, 1.0).orElseThrow();
        assertEquals(0, p[0], 1e-6); assertEquals(0, p[1], 1e-6);
    }
    @Test void givenLookRight_whenPinned_thenPointerMovesRight() {
        double[] p = ScreenGeometry.pinnedPoint(0f, 0f, 10f, 0f, 1.0).orElseThrow();
        assertTrue(p[0] > 0);
        assertEquals(Math.toRadians(10), p[0], 1e-6, "0.66.1: linear in the angle, not tan");
        assertEquals(0, p[1], 1e-6);
    }
    @Test void givenLookUp_whenPinned_thenPointerMovesUp() {
        double[] p = ScreenGeometry.pinnedPoint(0f, 0f, 0f, -10f, 1.0).orElseThrow();
        assertTrue(p[1] > 0);
    }
    @Test void givenLookBehind_whenPinned_thenEmpty() {
        assertTrue(ScreenGeometry.pinnedPoint(0f, 0f, 180f, 0f, 1.0).isEmpty());
    }
    @Test void givenStraightDown_whenPinned_thenNoNaN() {
        ScreenGeometry.pinnedPoint(0f, 0f, 0f, 90f, 1.0).ifPresent(p -> { assertFalse(Double.isNaN(p[0])); assertFalse(Double.isNaN(p[1])); });
        ScreenGeometry.pinnedPoint(0f, 90f, 0f, 90f, 1.0).ifPresent(p -> { assertFalse(Double.isNaN(p[0])); assertFalse(Double.isNaN(p[1])); });
    }
    @Test void givenLookRight_whenFloating_thenPointRightOnPlane() {
        double[] p = ScreenGeometry.floatingPoint(0f, 0f, 10f, 0f, 2.5).orElseThrow();
        assertEquals(2.5 * Math.tan(Math.toRadians(10)), p[0], 1e-6);
        assertEquals(0, p[1], 1e-6);
    }
    @Test void givenLookAway_whenFloating_thenEmpty() {
        assertTrue(ScreenGeometry.floatingPoint(0f, 0f, 120f, 0f, 2.5).isEmpty());
    }
    @Test void givenRotatedAnchor_whenSameView_thenBothModesAgreeAtCentre() {
        double[] a = ScreenGeometry.pinnedPoint(137f, -25f, 137f, -25f, 1.0).orElseThrow();
        double[] b = ScreenGeometry.floatingPoint(137f, -25f, 137f, -25f, 2.5).orElseThrow();
        assertEquals(0, a[0], 1e-6); assertEquals(0, b[1], 1e-6);
    }
}
