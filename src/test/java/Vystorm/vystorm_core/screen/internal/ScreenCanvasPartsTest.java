package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure parts of the screen canvas: changed-rectangle sender, press timing, pointer rate limit. */
class ScreenCanvasPartsTest {
    record Patch(int tile, int x, int y, int w, int h, byte[] colours) {}

    private static byte[] tile(byte fill) {
        byte[] t = new byte[128 * 128];
        java.util.Arrays.fill(t, fill);
        java.util.Arrays.fill(t, 0, 128, ScreenMapCodec.BIT_ZERO);   // a header row that never changes
        return t;
    }

    private static List<byte[]> tiles(int n, byte fill) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(tile(fill));
        return out;
    }

    private static List<byte[]> copy(List<byte[]> in) { return in.stream().map(byte[]::clone).toList(); }

    // ---- ScreenMapSender ----------------------------------------------------------------------------------------

    @Test void givenOnePixelChanged_whenFlushed_thenOneOnePixelPatchWithTheNewColour() {
        List<byte[]> shown = tiles(4, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        List<byte[]> next = copy(shown);
        next.get(2)[40 * 128 + 17] = 18;
        sender.stage(next);
        List<Patch> sent = new ArrayList<>();
        int spent = sender.flush(1 << 20, true, (t, x, y, w, h, c) -> sent.add(new Patch(t, x, y, w, h, c)));
        assertEquals(1, sent.size());
        Patch p = sent.get(0);
        assertEquals(List.of(2, 17, 40, 1, 1), List.of(p.tile(), p.x(), p.y(), p.w(), p.h()));
        assertArrayEquals(new byte[] { 18 }, p.colours());
        assertEquals(1 + ScreenMapSender.PATCH_OVERHEAD, spent);
        assertFalse(sender.pending());
    }

    @Test void givenNothingChanged_whenStaged_thenNothingPending() {
        List<byte[]> shown = tiles(2, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        sender.stage(copy(shown));
        assertFalse(sender.pending());
        assertEquals(0, sender.flush(1 << 20, true, (t, x, y, w, h, c) -> fail("nothing to send")));
    }

    @Test void givenTwoChangesBeforeAFlush_whenFlushed_thenOneUnionRectangleWithTheNewestPixels() {
        List<byte[]> shown = tiles(1, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        List<byte[]> a = copy(shown);
        a.get(0)[10 * 128 + 10] = 18;
        sender.stage(a);
        List<byte[]> b = copy(a);
        b.get(0)[20 * 128 + 30] = 30;
        b.get(0)[10 * 128 + 10] = 50;   // the first pixel changed again
        sender.stage(b);
        assertArrayEquals(new int[] { 10, 10, 31, 21 }, sender.pendingRect(0));
        List<Patch> sent = new ArrayList<>();
        sender.flush(1 << 20, true, (t, x, y, w, h, c) -> sent.add(new Patch(t, x, y, w, h, c)));
        Patch p = sent.get(0);
        assertEquals(21 * 11, p.colours().length);
        assertEquals(50, p.colours()[0], "the newest colour, not the one staged first");
        assertEquals(30, p.colours()[10 * 21 + 20]);
    }

    @Test void givenChangeBackToTheSentPixel_whenStagedTwice_thenTheRectangleStaysPending() {
        // the client still has 34; after 34 -> 18 -> 34 nothing would differ from the client, but sending is safe
        List<byte[]> shown = tiles(1, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        List<byte[]> a = copy(shown);
        a.get(0)[500] = 18;
        sender.stage(a);
        sender.stage(copy(shown));
        assertTrue(sender.pending());
        List<Patch> sent = new ArrayList<>();
        sender.flush(1 << 20, true, (t, x, y, w, h, c) -> sent.add(new Patch(t, x, y, w, h, c)));
        assertEquals(34, sent.get(0).colours()[0]);
    }

    @Test void givenFullRedrawOfFourTiles_whenBudgetFitsTwo_thenTwoPerTickInTurn() {
        List<byte[]> shown = tiles(4, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        sender.stage(tiles(4, (byte) 18));   // header row equal, rows 1..127 changed: 128 x 127 per tile
        int full = 128 * 127 + ScreenMapSender.PATCH_OVERHEAD;
        List<Integer> tick1 = new ArrayList<>(), tick2 = new ArrayList<>();
        assertEquals(2 * full, sender.flush(2 * full + 100, true, (t, x, y, w, h, c) -> tick1.add(t)));
        assertEquals(List.of(0, 1), tick1);
        assertTrue(sender.pending());
        sender.flush(2 * full + 100, true, (t, x, y, w, h, c) -> tick2.add(t));
        assertEquals(List.of(2, 3), tick2);
        assertFalse(sender.pending());
        assertEquals(4, sender.patchesSent());
        assertEquals(4L * 128 * 127, sender.pixelsSent());
    }

    @Test void givenBudgetSmallerThanOnePatch_whenMustSend_thenOnePatchStillGoes() {
        List<byte[]> shown = tiles(2, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        sender.stage(tiles(2, (byte) 18));
        List<Integer> sent = new ArrayList<>();
        sender.flush(100, true, (t, x, y, w, h, c) -> sent.add(t));
        assertEquals(List.of(0), sent, "progress every tick");
        sender.flush(100, false, (t, x, y, w, h, c) -> sent.add(t));
        assertEquals(List.of(0), sent, "not when the viewer already got something this tick");
    }

    @Test void givenBigPatchFirstAndSmallSecond_whenBudgetFitsOnlyTheSmall_thenTheSmallOneGoes() {
        List<byte[]> shown = tiles(2, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        List<byte[]> next = tiles(2, (byte) 34);
        next.set(0, tile((byte) 18));
        next.get(1)[300] = 18;
        sender.stage(next);
        List<Integer> sent = new ArrayList<>();
        sender.flush(1_000, false, (t, x, y, w, h, c) -> sent.add(t));
        assertEquals(List.of(1), sent);
        assertNotNull(sender.pendingRect(0));
    }

    @Test void givenSmallStrokeAcrossATileSeam_whenSent_thenBytesAreAFractionOfFullTiles() {
        // a 3 px brush moved 3 px per sample from x=110 to x=146 on a 2x2 canvas (crosses the seam at x=128)
        List<byte[]> shown = tiles(4, (byte) 34);
        ScreenMapSender sender = new ScreenMapSender(shown);
        byte[] canvas = new byte[256 * 254];
        java.util.Arrays.fill(canvas, (byte) 34);
        int samples = 0;
        long partial = 0;
        for (int x = 110; x <= 146; x += 3, samples++) {
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) canvas[(60 + dy) * 256 + x + dx] = 18;
            List<byte[]> next = new ArrayList<>();
            for (int ty = 0; ty < 2; ty++) for (int tx = 0; tx < 2; tx++) next.add(ScreenMapCodec.tile(canvas, 256, tx, ty, shown.get(0).clone()));
            sender.stage(next);
            partial += sender.flush(1 << 20, true, (t, x0, y0, w, h, c) -> { });
        }
        long full = (long) samples * 4 * (128 * 128 + ScreenMapSender.PATCH_OVERHEAD);   // Core 0.71.1: every tile on every setMap
        System.out.println("[screen canvas] stroke of " + samples + " samples: full tiles " + full + " bytes, changed rectangles " + partial + " bytes");
        assertTrue(partial * 500 < full, "partial " + partial + " vs full " + full);
    }

    // ---- paint demo canvas ---------------------------------------------------------------------------------------

    @Test void givenStrokeBetweenTwoSamples_whenPainted_thenTheLineBetweenIsFilledAndLiftEndsIt() {
        ScreenPaintDemo.Canvas c = new ScreenPaintDemo.Canvas(Vystorm.vystorm_core.screen.MapColors.table());
        c.strokeTo(10, 10);
        c.strokeTo(20, 10);
        for (int x = 9; x <= 21; x++) assertEquals(c.colour, c.pixels[10 * ScreenPaintDemo.W + x], "x " + x);
        assertEquals(c.background, c.pixels[10 * ScreenPaintDemo.W + 23]);
        c.lift();
        c.strokeTo(40, 40);
        assertEquals(c.background, c.pixels[25 * ScreenPaintDemo.W + 30], "no line from the lifted point");
        c.strokeTo(255, 253);   // the corner pixel: the stamp is clipped to the canvas
        assertEquals(c.colour, c.pixels[253 * ScreenPaintDemo.W + 255]);
        c.clear();
        assertEquals(c.background, c.pixels[10 * ScreenPaintDemo.W + 10]);
    }

    // ---- PanelHoles (C0 experiment) ----------------------------------------------------------------------------

    private static double area(List<double[]> rects) { return rects.stream().mapToDouble(r -> (r[2] - r[0]) * (r[3] - r[1])).sum(); }

    private static boolean overlaps(double[] a, double[] b) { return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]; }

    @Test void givenOneMapInside_whenCut_thenFourPiecesAroundItCoveringTheRest() {
        double[] outer = { -1, -1, 1, 1 }, map = { -0.5, -0.2, 0.3, 0.6 };
        List<double[]> pieces = PanelHoles.pieces(outer, List.of(map));
        assertEquals(4, pieces.size(), "below, left, right, above");
        for (double[] p : pieces) assertFalse(overlaps(p, map), "nothing over the map");
        for (int i = 0; i < pieces.size(); i++) for (int k = i + 1; k < pieces.size(); k++) assertFalse(overlaps(pieces.get(i), pieces.get(k)));
        assertEquals(4 - 0.8 * 0.8, area(pieces), 1e-9);
    }

    @Test void givenTwoMapsAndOneReachingTheEdge_whenCut_thenNoOverlapAndExactArea() {
        double[] outer = { 0, 0, 10, 6 };
        List<double[]> maps = List.of(new double[] { 1, 1, 4, 5 }, new double[] { 6, 2, 12, 4 });   // the second runs past the right edge
        List<double[]> pieces = PanelHoles.pieces(outer, maps);
        for (double[] p : pieces) for (double[] m : maps) assertFalse(overlaps(p, m));
        assertEquals(60 - 12 - 8, area(pieces), 1e-9);
        assertEquals(1, PanelHoles.pieces(outer, List.of()).size(), "no map: the whole panel");
    }

    // ---- ScreenPress --------------------------------------------------------------------------------------------

    @Test void givenHeldRightButton_whenUsesRepeatEvery200ms_thenOneDownAndOneUpAfterTheRelease() {
        ScreenPress press = new ScreenPress(350);
        List<String> events = new ArrayList<>();
        long[] uses = { 1_000, 1_200, 1_400, 1_600 };   // client repeats every 4 ticks
        long next = 0;
        for (long now = 1_000; now <= 2_200; now += 50) {   // one check per server tick
            if (press.released(now)) events.add("up@" + now);
            if (next < uses.length && uses[(int) next] == now) { if (press.use(now)) events.add("down@" + now); next++; }
        }
        assertEquals(List.of("down@1000", "up@1950"), events);
    }

    @Test void givenRepeatArrivingLate_whenWithinTheReleaseTime_thenTheHoldContinues() {
        ScreenPress press = new ScreenPress(350);
        assertTrue(press.use(0));
        assertFalse(press.released(340));
        assertFalse(press.use(340), "a repeat 140 ms late keeps the hold");
        assertFalse(press.released(680));
        assertTrue(press.released(690));
        assertTrue(press.use(800), "a new hold");
    }

    @Test void givenGapNoTickSaw_whenUseArrives_thenReleasedFirstThenANewDown() {
        ScreenPress press = new ScreenPress(350);
        press.use(0);
        assertTrue(press.released(2_000), "the caller asks released() before use()");
        assertTrue(press.use(2_000));
    }

    @Test void givenButtonDown_whenScreenEnds_thenEndReportsItOnce() {
        ScreenPress press = new ScreenPress(350);
        press.use(0);
        assertTrue(press.end());
        assertFalse(press.end());
        assertFalse(press.released(10_000));
        assertThrows(IllegalArgumentException.class, () -> new ScreenPress(ScreenPress.REPEAT_MILLIS));
    }

    // ---- ScreenPointerGate --------------------------------------------------------------------------------------

    @Test void givenManySamplesInOneTick_whenOffered_thenOnlyTheFirstGoes() {
        ScreenPointerGate gate = new ScreenPointerGate();
        assertTrue(gate.offer(1, "canvas", 0.1, 0.2));
        assertFalse(gate.offer(1, "canvas", 0.11, 0.2));
        assertFalse(gate.offer(1, "canvas", 0.12, 0.2));
        assertTrue(gate.offer(2, "canvas", 0.12, 0.2), "the newest position one tick later");
    }

    @Test void givenSamePosition_whenOfferedInLaterTicks_thenNothingNew() {
        ScreenPointerGate gate = new ScreenPointerGate();
        assertTrue(gate.offer(1, "canvas", 0.1, 0.2));
        assertFalse(gate.offer(2, "canvas", 0.1, 0.2));
        assertFalse(gate.offer(3, "canvas", 0.1 + 1e-7, 0.2));
        assertTrue(gate.offer(4, "palette", 0.1, 0.2), "another widget at the same place is new");
    }

    @Test void givenPointerLeft_whenItComesBackToTheSamePlace_thenSampledAgain() {
        ScreenPointerGate gate = new ScreenPointerGate();
        assertTrue(gate.offer(1, "canvas", 0.1, 0.2));
        assertFalse(gate.offer(2, null, 0.5, 0.5));
        assertTrue(gate.offer(3, "canvas", 0.1, 0.2));
    }
}
