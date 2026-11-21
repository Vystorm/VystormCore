package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 0.66.1: the pointer of PINNED screens moves linearly with the mouse. The shader (text.vsh) and the server
 * ({@link ScreenGeometry#pinnedPoint}) must agree, so the click lands where the pointer is drawn. {@link #drawn} is the
 * shader's formula in Java: from the camera rotation and the glyph's camera-relative position only.
 */
class ScreenPointerMappingTest {
    private static final double D = ScreenRuntime.PINNED_DISTANCE;

    /** The view-space point (at depth 1) where text.vsh draws the pointer's anchor vertex (hotspot left out). */
    static double[] drawn(float anchorYaw, float anchorPitch, float yaw, float pitch) {
        ScreenGeometry.Vec p = ScreenGeometry.direction(anchorYaw, anchorPitch).scale(D);   // glyph relative to the camera
        // the shader only knows the camera rotation: right and forward vectors in the world
        ScreenGeometry.Vec right = ScreenGeometry.right(yaw, pitch), forward = ScreenGeometry.direction(yaw, pitch);
        double camYaw = Math.atan2(-right.z(), -right.x());
        double camPitch = Math.asin(Math.max(-1, Math.min(1, -forward.y())));
        double turn = camYaw - Math.atan2(-p.x(), p.z());
        turn -= 2 * Math.PI * Math.floor((turn + Math.PI) / (2 * Math.PI));
        double nod = camPitch - Math.atan2(-p.y(), Math.hypot(p.x(), p.z()));
        return new double[] { turn, -nod };
    }

    /** Pixel of a view-space point (x, y, -1) on a window of this size and vertical field of view. */
    static double[] pixel(double x, double y, int width, int height, double fovDegrees) {
        double f = 1 / Math.tan(Math.toRadians(fovDegrees) / 2), aspect = (double) width / height;
        return new double[] { (x * f / aspect + 1) / 2 * width, (1 - y * f) / 2 * height };
    }

    @Test void givenEqualYawAndPitchSteps_whenMapped_thenEqualPointerStepsWithTheSameGainBothWays() {
        for (float anchorPitch : new float[] {0f, -35f, 25f}) {
            for (float nod = -30f; nod <= 30f; nod += 10f) {
                double[] last = null;
                for (float turn = -50f; turn <= 50f; turn += 2f) {
                    double[] p = ScreenGeometry.pinnedPoint(10f, anchorPitch, 10f + turn, anchorPitch + nod, D).orElseThrow();
                    if (last != null) {
                        assertEquals(Math.toRadians(2) * D, p[0] - last[0], 1e-9, "horizontal step at turn " + turn);
                        assertEquals(0, p[1] - last[1], 1e-9, "turning does not move the pointer up or down (no bent diagonals)");
                    }
                    last = p;
                }
            }
            double[] a = ScreenGeometry.pinnedPoint(10f, anchorPitch, 10f, anchorPitch, D).orElseThrow();
            double[] b = ScreenGeometry.pinnedPoint(10f, anchorPitch, 10f, anchorPitch + 2f, D).orElseThrow();
            assertEquals(Math.toRadians(2) * D, a[1] - b[1], 1e-9, "vertical gain equals the horizontal one");
        }
    }

    @Test void givenTheOldPerspectiveMapping_whenComparedAtTheEdge_thenItsStepWasMuchLarger() {
        // documents the cause: tan(θ) grows its step by 1/cos²θ, 1.5x at 35° from the centre
        double centre = Math.tan(Math.toRadians(1)) - Math.tan(0), edge = Math.tan(Math.toRadians(36)) - Math.tan(Math.toRadians(35));
        assertTrue(edge / centre > 1.45, "old step ratio " + edge / centre);
    }

    @Test void givenAGridOfViewAngles_whenDrawnAndHit_thenBothAreTheSamePointForEveryWindowShape() {
        int[][] windows = { {1920, 1080}, {1920, 1200}, {1024, 768}, {2560, 1080}, {854, 480} };
        for (float anchorYaw : new float[] {0f, 137f, -170f}) {
            for (float anchorPitch : new float[] {0f, -40f, 55f}) {
                for (float turn = -60f; turn <= 60f; turn += 7.5f) {
                    for (float nod = -45f; nod <= 45f; nod += 7.5f) {
                        float yaw = anchorYaw + turn, pitch = Math.max(-90f, Math.min(90f, anchorPitch + nod));
                        double[] hit = ScreenGeometry.pinnedPoint(anchorYaw, anchorPitch, yaw, pitch, D).orElseThrow();
                        double[] draw = drawn(anchorYaw, anchorPitch, yaw, pitch);
                        assertEquals(hit[0] / D, draw[0], 1e-6, "u at " + turn + "/" + nod);
                        assertEquals(hit[1] / D, draw[1], 1e-6, "v at " + turn + "/" + nod);
                        for (int[] w : windows) {
                            double[] a = pixel(hit[0] / D, hit[1] / D, w[0], w[1], 70), b = pixel(draw[0], draw[1], w[0], w[1], 70);
                            assertEquals(a[0], b[0], 0.01, "x pixel " + w[0] + "x" + w[1]);
                            assertEquals(a[1], b[1], 0.01, "y pixel " + w[0] + "x" + w[1]);
                        }
                    }
                }
            }
        }
    }

    @Test void givenAWidePanel_whenTheHeadTurnsToItsCorners_thenEveryCornerIsReached() {
        double halfW = 0.9, halfH = 0.5;   // a 1.8 x 1.0 panel, wider than the widest Core layout
        for (int sx : new int[] {-1, 1}) for (int sy : new int[] {-1, 1}) {
            float yaw = (float) Math.toDegrees(sx * halfW / D), pitch = (float) Math.toDegrees(-sy * halfH / D);
            double[] p = ScreenGeometry.pinnedPoint(0f, 0f, yaw, pitch, D).orElseThrow();
            assertEquals(sx * halfW, p[0], 1e-5);
            assertEquals(sy * halfH, p[1], 1e-5);
        }
    }

    @Test void givenTheShader_whenRead_thenItUsesTheLinearFormula() {
        String shader = new String(ScreenShader.packFiles().get("assets/minecraft/shaders/core/text.vsh"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(shader.contains("float turn = camYaw - atan(-p.x, p.z);"), "same yaw formula as drawn()");
        assertTrue(shader.contains("float nod = camPitch - atan(-p.y, length(p.xz));"));
        assertFalse(shader.contains("view.xy = -view.xy;"), "the perspective mirror is gone");
    }
}
