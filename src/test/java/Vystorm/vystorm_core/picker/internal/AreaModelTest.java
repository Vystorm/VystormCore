package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.picker.AreaHeight;
import Vystorm.vystorm_core.picker.AreaRules;
import Vystorm.vystorm_core.picker.PickFaces;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The pure parts of the area pick (0.71.0): caps, limits, the two-corner steps, outline, protection, layouts. */
class AreaModelTest {
    private static final UUID W = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final AreaCaps CAPS = AreaCaps.HARD;

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }

    private static Picked.Area area(int x0, int y0, int z0, int x1, int y1, int z1) { return new Picked.Area(W, at(x0, y0, z0), at(x1, y1, z1)); }

    // ------------------------------------------------------------------ caps (config)

    @Test void givenNoSection_whenRead_thenTheDesignCaps() {
        AreaCaps c = WorldPickConfig.read(new YamlConfiguration()).area();
        assertEquals(new AreaCaps(512, 384, 512, 50_000_000L), c);
        assertEquals(AreaCaps.HARD, c);
    }

    @Test void givenLowerOrHigherCaps_whenRead_thenLowerKept_andHigherCappedAtTheHardCaps() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pickers.world.max-area.x", 100);
        y.set("pickers.world.max-area.y", 9999);
        y.set("pickers.world.max-area.z", 0);
        y.set("pickers.world.max-area.volume", 10_000_000_000L);
        AreaCaps c = WorldPickConfig.read(y).area();
        assertEquals(new AreaCaps(100, 384, 1, 50_000_000L), c);
    }

    // ------------------------------------------------------------------ box from corners and height mode

    @Test void givenPickedHeight_whenBoxBuilt_thenTheTwoCorners() {
        Picked.Area a = AreaLimits.box(W, at(5, 70, 5), at(-2, 64, 9), AreaRules.DEFAULT, false, -64, 320);
        assertEquals(at(-2, 64, 5), a.min());
        assertEquals(at(5, 70, 9), a.max());
    }

    @Test void givenFullHeight_whenBoxBuilt_thenWholeWorldHeight_andAskOnlyWhenExpanded() {
        AreaRules full = AreaRules.builder().height(AreaHeight.FULL).build();
        Picked.Area a = AreaLimits.box(W, at(0, 70, 0), at(3, 72, 3), full, false, -64, 320);
        assertEquals(-64, a.min().y());
        assertEquals(319, a.max().y());
        AreaRules ask = AreaRules.builder().height(AreaHeight.ASK).build();
        assertEquals(70, AreaLimits.box(W, at(0, 70, 0), at(3, 72, 3), ask, false, -64, 320).min().y());
        assertEquals(-64, AreaLimits.box(W, at(0, 70, 0), at(3, 72, 3), ask, true, -64, 320).min().y());
        // expanded means nothing without ASK
        assertEquals(70, AreaLimits.box(W, at(0, 70, 0), at(3, 72, 3), AreaRules.DEFAULT, true, -64, 320).min().y());
    }

    @Test void givenFixedHeight_whenBoxBuilt_thenUpFromTheLowerCorner_andMovedDownAtTheTop() {
        AreaRules fixed = AreaRules.builder().fixedHeight(10).build();
        Picked.Area a = AreaLimits.box(W, at(0, 80, 0), at(3, 64, 3), fixed, false, -64, 320);
        assertEquals(64, a.min().y());
        assertEquals(73, a.max().y());
        Picked.Area top = AreaLimits.box(W, at(0, 315, 0), at(3, 318, 3), fixed, false, -64, 320);
        assertEquals(310, top.min().y());
        assertEquals(319, top.max().y());
        assertEquals(10, top.sizeY());
        // review finding: a huge fixed height wrapped around; now it is the whole world height
        Picked.Area huge = AreaLimits.box(W, at(0, 80, 0), at(3, 64, 3), AreaRules.builder().fixedHeight(Integer.MAX_VALUE).build(), false, -64, 320);
        assertEquals(-64, huge.min().y());
        assertEquals(319, huge.max().y());
    }

    @Test void givenCornersOutsideTheWorldHeight_whenBoxBuilt_thenClampedIntoTheWorld() {
        Picked.Area a = AreaLimits.box(W, at(0, -100, 0), at(1, 400, 1), AreaRules.DEFAULT, false, -64, 320);
        assertEquals(-64, a.min().y());
        assertEquals(319, a.max().y());
    }

    // ------------------------------------------------------------------ limits

    @Test void givenAnAreaWithinEveryLimit_whenChecked_thenOk() {
        assertTrue(AreaLimits.check(area(0, 0, 0, 11, 4, 8), true, AreaRules.DEFAULT, CAPS, false).ok());
    }

    @Test void givenCornersInTwoWorlds_whenChecked_thenOtherWorld() {
        AreaLimits.Verdict v = AreaLimits.check(area(0, 0, 0, 1, 1, 1), false, AreaRules.DEFAULT, CAPS, false);
        assertEquals("other-world", v.reason());
    }

    @Test void givenOneAxisTooLong_whenChecked_thenTooBigWithTheSizeAndTheEffectiveMaximum() {
        AreaLimits.Verdict v = AreaLimits.check(area(0, 0, 0, 599, 19, 39), true, AreaRules.DEFAULT, CAPS, false);
        assertEquals("too-big", v.reason());
        assertEquals("600 x 20 x 40", v.arg("size"));
        assertEquals("512 x 384 x 512", v.arg("max"));
        AreaRules own = AreaRules.builder().maxSize(64, 128, 64).build();
        AreaLimits.Verdict w = AreaLimits.check(area(0, 0, 0, 64, 0, 0), true, own, CAPS, false);
        assertEquals("too-big", w.reason());
        assertEquals("64 x 128 x 64", w.arg("max"));
        // each axis on its own
        assertTrue(AreaLimits.check(area(0, 0, 0, 63, 127, 63), true, own, CAPS, false).ok());
        assertEquals("too-big", AreaLimits.check(area(0, 0, 0, 63, 128, 63), true, own, CAPS, false).reason());
        assertEquals("too-big", AreaLimits.check(area(0, 0, 0, 63, 127, 64), true, own, CAPS, false).reason());
    }

    @Test void givenAPluginAskingMoreThanCore_whenChecked_thenCoresCapWins() {
        AreaRules huge = AreaRules.builder().maxSize(10_000, 10_000, 10_000).build();
        assertEquals("too-big", AreaLimits.check(area(0, 0, 0, 512, 0, 0), true, huge, CAPS, false).reason());
        AreaCaps lower = new AreaCaps(100, 100, 100, 1_000_000);
        assertEquals("too-big", AreaLimits.check(area(0, 0, 0, 100, 0, 0), true, huge, lower, false).reason());
        assertTrue(AreaLimits.check(area(0, 0, 0, 99, 0, 0), true, huge, lower, false).ok());
    }

    @Test void givenFullHeight_whenChecked_thenThePluginsYLimitIsNotApplied_butCoresIs() {
        AreaRules own = AreaRules.builder().maxSize(64, 16, 64).height(AreaHeight.ASK).build();
        assertTrue(AreaLimits.check(area(0, -64, 0, 9, 319, 9), true, own, CAPS, true).ok());
        assertEquals("too-big", AreaLimits.check(area(0, -64, 0, 9, 319, 9), true, own, CAPS, false).reason());
        assertEquals("too-big", AreaLimits.check(area(0, -64, 0, 9, 335, 9), true, own, CAPS, true).reason());
    }

    @Test void givenTooSmall_whenChecked_thenTooSmallWithTheMinimum() {
        AreaRules own = AreaRules.builder().minSize(3, 2, 3).build();
        AreaLimits.Verdict v = AreaLimits.check(area(0, 0, 0, 2, 0, 2), true, own, CAPS, false);
        assertEquals("too-small", v.reason());
        assertEquals("3 x 1 x 3", v.arg("size"));
        assertEquals("3 x 2 x 3", v.arg("min"));
    }

    @Test void givenTooManyBlocks_whenChecked_thenTooManyWithBothCounts() {
        AreaRules own = AreaRules.builder().maxVolume(1000).build();
        AreaLimits.Verdict v = AreaLimits.check(area(0, 0, 0, 9, 9, 10), true, own, CAPS, false);
        assertEquals("too-many", v.reason());
        assertEquals("1,100", v.arg("blocks"));
        assertEquals("1,000", v.arg("max"));
        // Core's volume cap: 512 x 384 x 512 is within every axis but above 50,000,000 blocks
        AreaLimits.Verdict big = AreaLimits.check(area(0, -64, 0, 511, 319, 511), true, AreaRules.DEFAULT, CAPS, false);
        assertEquals("too-many", big.reason());
        assertEquals("50,000,000", big.arg("max"));
    }

    @Test void givenTexts_whenFormatted_thenSizeAndGroupedCount() {
        assertEquals("12 x 5 x 9", AreaLimits.size(area(0, 0, 0, 11, 4, 8)));
        assertEquals("7,497", AreaLimits.count(7497));
        assertEquals("1", AreaLimits.count(1));
    }

    // ------------------------------------------------------------------ the two-corner steps

    private static AreaSteps.Corner corner(int x, int y, int z) { return new AreaSteps.Corner(W, at(x, y, z)); }

    @Test void givenANewPick_whenStarted_thenFirstCornerWithoutAnchor() {
        AreaSteps s = new AreaSteps();
        assertEquals(AreaSteps.Step.FIRST, s.step());
        assertEquals(1, s.choosing());
        assertTrue(s.anchor().isEmpty());
        assertFalse(s.canRedoFirst());
        assertFalse(s.canRedoSecond());
    }

    @Test void givenFirstThenSecondCorner_whenSet_thenConfirmWithBoth_andTheFirstIsTheAnchorOfTheSecond() {
        AreaSteps s = new AreaSteps();
        s.set(corner(1, 2, 3));
        assertEquals(AreaSteps.Step.SECOND, s.step());
        assertEquals(2, s.choosing());
        assertEquals(corner(1, 2, 3), s.anchor().orElseThrow());
        assertTrue(s.canRedoFirst());
        assertFalse(s.canRedoSecond());
        s.set(corner(9, 9, 9));
        assertEquals(AreaSteps.Step.CONFIRM, s.step());
        assertEquals(0, s.choosing());
        assertEquals(corner(1, 2, 3), s.first());
        assertEquals(corner(9, 9, 9), s.second());
        assertThrows(IllegalStateException.class, () -> s.set(corner(0, 0, 0)));
    }

    @Test void givenTheSecondStep_whenFirstRedone_thenStartsOver() {
        AreaSteps s = new AreaSteps();
        s.set(corner(1, 2, 3));
        assertTrue(s.redoFirst());
        assertEquals(AreaSteps.Step.FIRST, s.step());
        assertNull(s.first());
        assertTrue(s.anchor().isEmpty());
        s.set(corner(4, 4, 4));
        assertEquals(AreaSteps.Step.SECOND, s.step());
    }

    @Test void givenTheConfirmView_whenFirstRedone_thenTheSecondIsTheAnchor_andTheNewFirstGoesBackToConfirm() {
        AreaSteps s = confirmed();
        assertTrue(s.redoFirst());
        assertEquals(AreaSteps.Step.FIRST, s.step());
        assertEquals(1, s.choosing());
        assertEquals(corner(9, 9, 9), s.anchor().orElseThrow());
        s.set(corner(5, 5, 5));
        assertEquals(AreaSteps.Step.CONFIRM, s.step());
        assertEquals(corner(5, 5, 5), s.first());
        assertEquals(corner(9, 9, 9), s.second());
    }

    @Test void givenTheConfirmView_whenSecondRedone_thenTheFirstIsTheAnchor_andTheNewSecondGoesBackToConfirm() {
        AreaSteps s = confirmed();
        assertTrue(s.redoSecond());
        assertEquals(AreaSteps.Step.SECOND, s.step());
        assertNull(s.second());
        assertEquals(corner(1, 2, 3), s.anchor().orElseThrow());
        s.set(corner(7, 7, 7));
        assertEquals(AreaSteps.Step.CONFIRM, s.step());
        assertEquals(corner(7, 7, 7), s.second());
    }

    @Test void givenStepsWhereARedoMakesNoSense_whenAsked_thenRefused() {
        AreaSteps s = new AreaSteps();
        assertFalse(s.redoFirst());
        assertFalse(s.redoSecond());
        s.set(corner(1, 1, 1));
        assertFalse(s.redoSecond());
        assertEquals(AreaSteps.Step.SECOND, s.step());
    }

    @Test void givenAskHeight_whenToggledInTheConfirmView_thenFullHeight_andBackAgain_otherModesAndStepsRefuse() {
        AreaRules ask = AreaRules.builder().height(AreaHeight.ASK).build();
        AreaSteps s = new AreaSteps();
        assertFalse(s.toggleHeight(ask));          // not in the confirm view yet
        s.set(corner(1, 2, 3));
        s.set(corner(9, 9, 9));
        assertFalse(s.fullHeight(ask));
        assertTrue(s.toggleHeight(ask));
        assertTrue(s.expanded());
        assertTrue(s.fullHeight(ask));
        assertTrue(s.toggleHeight(ask));
        assertFalse(s.fullHeight(ask));
        assertFalse(s.toggleHeight(AreaRules.DEFAULT));
        assertFalse(s.toggleHeight(AreaRules.builder().height(AreaHeight.FULL).build()));
        assertTrue(s.fullHeight(AreaRules.builder().height(AreaHeight.FULL).build()));
    }

    @Test void givenFullHeightChosen_whenACornerIsRedone_thenItStaysChosen() {
        AreaRules ask = AreaRules.builder().height(AreaHeight.ASK).build();
        AreaSteps s = confirmed();
        s.toggleHeight(ask);
        s.redoSecond();
        s.set(corner(8, 8, 8));
        assertTrue(s.fullHeight(ask));
    }

    private static AreaSteps confirmed() {
        AreaSteps s = new AreaSteps();
        s.set(corner(1, 2, 3));
        s.set(corner(9, 9, 9));
        return s;
    }

    // ------------------------------------------------------------------ outline

    @Test void givenASmallBoxNearby_whenOutlined_thenAllTwelveEdgesTheAnchorAndTheAimedCell() {
        Picked.Area box = area(0, 64, 0, 4, 66, 4);
        List<Point3> pts = AreaOutline.points(box, at(0, 64, 0), at(4, 66, 4), new Point3(2, 66, -3), 48, 450);
        assertFalse(pts.isEmpty());
        assertTrue(pts.size() <= 450);
        // the eight box corners are drawn (box spans the outer faces of the cells)
        for (double x : new double[] {0, 5}) for (double y : new double[] {64, 67}) for (double z : new double[] {0, 5}) {
            final double fx = x, fy = y, fz = z;
            assertTrue(pts.stream().anyMatch(p -> Math.abs(p.x() - fx) < 0.05 && Math.abs(p.y() - fy) < 0.05 && Math.abs(p.z() - fz) < 0.05), x + " " + y + " " + z);
        }
    }

    /** Review finding: an edge whose nearest point to the eye lies before its start was not drawn at all. */
    @Test void givenTheEyeOutsideOnTheLowSideOfEveryAxis_whenOutlined_thenEachOfTheTwelveEdgesHasInnerPoints() {
        Picked.Area box = area(0, 64, 0, 9, 69, 9);
        for (Point3 eye : new Point3[] {new Point3(-3, 66, 4), new Point3(4, 66, -3), new Point3(4, 60, 4), new Point3(-3, 60, -3), new Point3(13, 72, 13)}) {
            List<Point3> pts = AreaOutline.points(box, null, null, eye, 48, 450);
            Point3[] c = AreaOutline.corners(box);
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] e : edges) {
                Point3 a = c[e[0]], b = c[e[1]];
                long inner = pts.stream().filter(p -> onSegment(p, a, b) && p.distanceSquared(a) > 0.01 && p.distanceSquared(b) > 0.01).count();
                assertTrue(inner >= 5, "eye " + eye + " edge " + e[0] + "-" + e[1] + ": " + inner + " inner points");
            }
        }
    }

    private static boolean onSegment(Point3 p, Point3 a, Point3 b) {
        Point3 ab = b.subtract(a), ap = p.subtract(a);
        double len2 = ab.x() * ab.x() + ab.y() * ab.y() + ab.z() * ab.z();
        double t = (ap.x() * ab.x() + ap.y() * ab.y() + ap.z() * ab.z()) / len2;
        if (t < 0 || t > 1) return false;
        return p.distanceSquared(a.add(ab.scale(t))) < 1e-6;
    }

    @Test void givenTheLargestBox_whenOutlined_thenNeverMoreThanTheCap_andNothingBeyondThePreviewDistance() {
        Picked.Area box = area(-256, -64, -256, 255, 319, 255);
        Point3 eye = new Point3(-250, -60, -250);   // near the bottom corner, where three long edges meet
        for (int cap : new int[] {24, 120, 450, 512}) {
            List<Point3> pts = AreaOutline.points(box, at(-256, -64, -256), at(255, 319, 255), eye, 48, cap);
            assertTrue(pts.size() <= cap, cap + ": " + pts.size());
            assertTrue(pts.size() >= Math.min(cap, 100), "the room is used: " + pts.size());
            for (Point3 p : pts) assertTrue(p.distanceSquared(eye) <= 48.0 * 48 + 1e-6, "beyond the distance: " + p);
        }
    }

    @Test void givenALongEdge_whenOutlined_thenPointsThinOutWithTheDistance() {
        // one long edge along x at the eye's height, the eye at its start: count points in the near and the far stretch
        Picked.Area box = area(0, 64, 0, 200, 64, 0);
        Point3 eye = new Point3(0, 65.5, -1);
        List<Point3> pts = AreaOutline.points(box, null, null, eye, 48, 450);
        long near = pts.stream().filter(p -> Math.abs(p.y() - 64) < 0.1 && Math.abs(p.z()) < 0.1 && p.x() >= 0 && p.x() < 10).count();
        long far = pts.stream().filter(p -> Math.abs(p.y() - 64) < 0.1 && Math.abs(p.z()) < 0.1 && p.x() >= 36 && p.x() < 46).count();
        assertTrue(near > far * 2, "near " + near + ", far " + far);
        assertTrue(far >= 1, "the far stretch still shows: " + far);
    }

    @Test void givenABoxFarAway_whenOutlined_thenOnlyTheVisibleCellsOrNothing() {
        Picked.Area box = area(1000, 64, 1000, 1010, 70, 1010);
        assertTrue(AreaOutline.points(box, null, null, new Point3(0, 64, 0), 48, 450).isEmpty());
    }

    @Test void givenNoBoxYet_whenOutlined_thenTheAimedCellOnly() {
        List<Point3> pts = AreaOutline.points(null, null, at(3, 64, 3), new Point3(0, 65, 0), 48, 450);
        assertFalse(pts.isEmpty());
        for (Point3 p : pts) assertTrue(p.x() > 2.9 && p.x() < 4.1 && p.z() > 2.9 && p.z() < 4.1, p.toString());
    }

    // ------------------------------------------------------------------ protection: preview

    private static final class Asker implements AreaGuard.Asker {
        final Map<Long, EditVerdict> answers = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        EditVerdict fallback = new EditVerdict.Allow();
        RuntimeException failure;
        @Override public EditVerdict ask(int cx, int cz, int minY, int maxY) {
            asked.add(cx + "," + cz + "," + minY + "," + maxY);
            if (failure != null) throw failure;
            return answers.getOrDefault(key(cx, cz), fallback);
        }
        static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
    }

    @Test void givenABox_whenChunksCounted_thenEveryChunkItTouches() {
        assertEquals(1, AreaGuard.chunks(area(0, 0, 0, 15, 0, 15)).size());
        assertEquals(4, AreaGuard.chunks(area(15, 0, 15, 16, 0, 16)).size());
        assertEquals(33 * 33, AreaGuard.chunks(area(-8, 0, -8, 503, 0, 503)).size());
    }

    @Test void givenAllAllowed_whenPreviewAskedWithinBudget_thenAllowed_andEachChunkOnceWithTheBoxHeight() {
        Asker a = new Asker();
        AtomicLong clock = new AtomicLong(0);
        AreaGuard.Preview p = new AreaGuard.Preview(a, clock::get);
        assertEquals(AreaGuard.State.ALLOWED, p.ask(area(0, 60, 0, 31, 70, 31), 64).state());
        assertEquals(4, a.asked.size());
        assertTrue(a.asked.contains("0,0,60,70"));
        // asked again: answered from the cache
        assertEquals(AreaGuard.State.ALLOWED, p.ask(area(0, 60, 0, 31, 70, 31), 64).state());
        assertEquals(4, a.asked.size());
    }

    @Test void givenMoreChunksThanTheBudget_whenPreviewAsked_thenPendingUntilAllAsked() {
        Asker a = new Asker();
        AreaGuard.Preview p = new AreaGuard.Preview(a, () -> 0L);
        Picked.Area box = area(0, 0, 0, 16 * 10 - 1, 0, 16 * 10 - 1);   // 100 chunks
        assertEquals(AreaGuard.State.PENDING, p.ask(box, 40).state());
        assertEquals(40, a.asked.size());
        assertEquals(AreaGuard.State.PENDING, p.ask(box, 40).state());
        assertEquals(AreaGuard.State.ALLOWED, p.ask(box, 40).state());
        assertEquals(100, a.asked.size());
    }

    @Test void givenADeniedOrPartialChunk_whenPreviewAsked_thenDeniedWinsOverPartial() {
        Asker a = new Asker();
        a.answers.put(Asker.key(1, 0), new EditVerdict.Partial((x, y, z) -> x < 20));
        AreaGuard.Preview p = new AreaGuard.Preview(a, () -> 0L);
        assertEquals(AreaGuard.State.PARTIAL, p.ask(area(0, 0, 0, 47, 0, 15), 64).state());
        a.answers.put(Asker.key(2, 0), new EditVerdict.Deny("regions"));
        AreaGuard.Preview q = new AreaGuard.Preview(a, () -> 0L);
        AreaGuard.Answer ans = q.ask(area(0, 0, 0, 47, 0, 15), 64);
        assertEquals(AreaGuard.State.DENIED, ans.state());
        assertEquals("regions", ans.reason());
    }

    @Test void givenACachedAnswer_whenOldOrAnotherHeight_thenAskedAgain() {
        Asker a = new Asker();
        AtomicLong clock = new AtomicLong(0);
        AreaGuard.Preview p = new AreaGuard.Preview(a, clock::get);
        p.ask(area(0, 0, 0, 15, 10, 15), 64);
        p.ask(area(0, 0, 0, 15, 20, 15), 64);   // the box grew upwards: the y range is part of the ask
        assertEquals(2, a.asked.size());
        clock.set(AreaGuard.CACHE_MS + 1);
        p.ask(area(0, 0, 0, 15, 20, 15), 64);
        assertEquals(3, a.asked.size());
    }

    @Test void givenAGuardThatThrows_whenPreviewAsked_thenDeniedAsGuardFailed() {
        Asker a = new Asker();
        a.failure = new IllegalStateException("boom");
        assertEquals("guard-failed", new AreaGuard.Preview(a, () -> 0L).ask(area(0, 0, 0, 1, 1, 1), 64).reason());
    }

    // ------------------------------------------------------------------ protection: the full check

    private static AreaGuard.Scan scan(Picked.Area box, Asker a, List<Runnable> offThread, long[] nanos, long deadline) {
        return new AreaGuard.Scan(box, a, offThread::add, () -> nanos[0], deadline);
    }

    @Test void givenAllowedChunks_whenScannedInSlices_thenAllowedAfterEnoughSteps_andNoCellWorkOffThread() {
        Asker a = new Asker();
        List<Runnable> off = new ArrayList<>();
        AreaGuard.Scan s = scan(area(0, 0, 0, 16 * 10 - 1, 0, 16 * 10 - 1), a, off, new long[] {0}, Long.MAX_VALUE);
        for (int i = 0; i < 3; i++) { s.step(1_000_000, 40); }
        assertEquals(AreaGuard.State.ALLOWED, s.answer().state());
        assertEquals(100, a.asked.size());
        assertTrue(off.isEmpty());
        s.step(1_000_000, 40);
        assertEquals(100, a.asked.size(), "a finished scan asks nothing more");
    }

    @Test void givenTheTimeBudget_whenAStepRunsOutOfTime_thenItStopsAndGoesOnNextTime() {
        Asker a = new Asker();
        long[] nanos = {0};
        AreaGuard.Asker slow = (cx, cz, y0, y1) -> { nanos[0] += 400_000; return a.ask(cx, cz, y0, y1); };
        AreaGuard.Scan s = new AreaGuard.Scan(area(0, 0, 0, 16 * 10 - 1, 0, 16 * 10 - 1), slow, r -> fail("no cells"), () -> nanos[0], Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        assertEquals(3, a.asked.size(), "three asks of 0.4 ms fit a 1 ms budget (the third crosses it)");
        assertEquals(AreaGuard.State.PENDING, s.answer().state());
    }

    @Test void givenADeniedChunk_whenScanned_thenDeniedAtOnceWithTheGuardsKey() {
        Asker a = new Asker();
        a.answers.put(Asker.key(0, 1), new EditVerdict.Deny("estates"));
        AreaGuard.Scan s = scan(area(0, 0, 0, 47, 0, 47), a, new ArrayList<>(), new long[] {0}, Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        assertEquals(AreaGuard.State.DENIED, s.answer().state());
        assertEquals("estates", s.answer().reason());
        assertTrue(a.asked.size() < 9, "stops at the first deny: " + a.asked.size());
    }

    @Test void givenPartialChunks_whenScanned_thenCellsAreTestedOffThread_andTheFirstDeniedCellIsNamed() {
        Asker a = new Asker();
        a.answers.put(Asker.key(1, 0), new EditVerdict.Partial((x, y, z) -> !(x == 20 && y == 65 && z == 3)));
        List<Runnable> off = new ArrayList<>();
        AreaGuard.Scan s = scan(area(0, 64, 0, 31, 66, 15), a, off, new long[] {0}, Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        assertEquals(AreaGuard.State.PENDING, s.answer().state(), "cells still to test");
        assertEquals(1, off.size());
        off.get(0).run();
        assertEquals(AreaGuard.State.DENIED, s.answer().state());
        assertEquals("partial", s.answer().reason());
        assertEquals(at(20, 65, 3), s.answer().cell());
    }

    @Test void givenPartialChunksThatAllowTheBox_whenScanned_thenAllowed_andOnlyCellsInTheBoxTested() {
        Asker a = new Asker();
        AtomicInteger tested = new AtomicInteger();
        a.answers.put(Asker.key(0, 0), new EditVerdict.Partial((x, y, z) -> { tested.incrementAndGet(); return x >= 2; }));
        List<Runnable> off = new ArrayList<>();
        AreaGuard.Scan s = scan(area(2, 64, 2, 5, 65, 4), a, off, new long[] {0}, Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        off.forEach(Runnable::run);
        assertEquals(AreaGuard.State.ALLOWED, s.answer().state());
        assertEquals(4 * 2 * 3, tested.get());
    }

    @Test void givenACellTestThatThrowsOrTakesTooLong_whenScanned_thenFailedWithTheReason() {
        Asker a = new Asker();
        a.answers.put(Asker.key(0, 0), new EditVerdict.Partial((x, y, z) -> { throw new IllegalStateException("bad"); }));
        List<Runnable> off = new ArrayList<>();
        AreaGuard.Scan s = scan(area(0, 0, 0, 3, 3, 3), a, off, new long[] {0}, Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        off.forEach(Runnable::run);
        assertEquals(AreaGuard.State.FAILED, s.answer().state());
        assertEquals("guard-failed", s.answer().reason());

        long[] nanos = {0};
        Asker b = new Asker();
        b.answers.put(Asker.key(0, 0), new EditVerdict.Partial((x, y, z) -> { nanos[0] += 1000; return true; }));
        List<Runnable> off2 = new ArrayList<>();
        AreaGuard.Scan slow = scan(area(0, 0, 0, 15, 200, 15), b, off2, nanos, 5_000_000);
        slow.step(1_000_000, 1000);
        off2.forEach(Runnable::run);
        assertEquals(AreaGuard.State.FAILED, slow.answer().state());
        assertEquals("too-slow", slow.answer().reason());
    }

    @Test void givenACancelledScan_whenStepsOrCellsRun_thenNothingMoreIsAsked() {
        Asker a = new Asker();
        a.answers.put(Asker.key(0, 0), new EditVerdict.Partial((x, y, z) -> { fail("cancelled scans test no cells"); return true; }));
        List<Runnable> off = new ArrayList<>();
        AreaGuard.Scan s = scan(area(0, 0, 0, 3, 3, 3), a, off, new long[] {0}, Long.MAX_VALUE);
        s.step(1_000_000, 1000);
        s.cancel();
        off.forEach(Runnable::run);
        assertEquals(AreaGuard.State.FAILED, s.answer().state());
        assertEquals("cancelled", s.answer().reason());
        int before = a.asked.size();
        s.step(1_000_000, 1000);
        assertEquals(before, a.asked.size());
    }

    // ------------------------------------------------------------------ layouts

    @Test void givenTheConfirmView_whenLaidOut_thenUseRedoBothCancel_andFullHeightOnlyWhenAllowed() {
        List<AreaConfirmLayout.Slot> plain = AreaConfirmLayout.layout(false);
        assertEquals(List.of(AreaConfirmLayout.Action.INFO, AreaConfirmLayout.Action.BLOCKS, AreaConfirmLayout.Action.USE, AreaConfirmLayout.Action.REDO_FIRST,
                AreaConfirmLayout.Action.REDO_SECOND, AreaConfirmLayout.Action.CANCEL), plain.stream().map(AreaConfirmLayout.Slot::action).toList());
        List<AreaConfirmLayout.Slot> ask = AreaConfirmLayout.layout(true);
        assertTrue(ask.stream().anyMatch(s -> s.action() == AreaConfirmLayout.Action.HEIGHT));
        for (AreaConfirmLayout.Slot s : ask) assertTrue(s.slot() >= 0 && s.slot() < AreaConfirmLayout.SIZE);
        assertEquals(ask.size(), ask.stream().map(AreaConfirmLayout.Slot::slot).distinct().count());
        // Cancel sits where it sits in the options chest, so a habit never confirms by mistake
        int cancel = ask.stream().filter(s -> s.action() == AreaConfirmLayout.Action.CANCEL).findFirst().orElseThrow().slot();
        int optionsCancel = WorldOptionsLayout.layout(PickKind.BLOCK).stream().filter(s -> s.action() == WorldOptionsLayout.Action.CANCEL).findFirst().orElseThrow().slot();
        assertEquals(optionsCancel, cancel);
    }

    @Test void givenAnAreaPick_whenOptionsLaidOut_thenBlockBelowTypeAndRedoOnlyAfterTheFirstCorner() {
        List<WorldOptionsLayout.Action> first = WorldOptionsLayout.layout(PickKind.AREA, false).stream().map(WorldOptionsLayout.Slot::action).toList();
        assertEquals(List.of(WorldOptionsLayout.Action.INFO, WorldOptionsLayout.Action.STAND, WorldOptionsLayout.Action.TYPE,
                WorldOptionsLayout.Action.RESUME, WorldOptionsLayout.Action.CANCEL), first);
        List<WorldOptionsLayout.Slot> second = WorldOptionsLayout.layout(PickKind.AREA, true);
        assertTrue(second.stream().anyMatch(s -> s.action() == WorldOptionsLayout.Action.REDO_FIRST));
        assertEquals("stand-block", second.stream().filter(s -> s.action() == WorldOptionsLayout.Action.STAND).findFirst().orElseThrow().key());
        assertEquals(second.size(), second.stream().map(WorldOptionsLayout.Slot::slot).distinct().count());
        // single targets never offer a redo
        assertFalse(WorldOptionsLayout.layout(PickKind.BLOCK, true).stream().anyMatch(s -> s.action() == WorldOptionsLayout.Action.REDO_FIRST));
    }

    // ------------------------------------------------------------------ targets

    @Test void givenAnAreaPickAimingAtABlock_whenResolved_thenThatCellAsAOneCellArea_evenWhenTheBlockFilterSaidNo() {
        WorldTargets.Hit.OnBlock hit = new WorldTargets.Hit.OnBlock(at(4, 64, 4), BlockFace.UP, "barrier", false, false);
        WorldTargets.Resolution r = WorldTargets.resolve(PickKind.AREA, W, hit, PickFaces.ANY, true);
        assertTrue(r.ok());
        assertEquals(new Picked.Area(W, at(4, 64, 4), at(4, 64, 4)), r.target());
        assertEquals(WorldTargets.NOTHING, WorldTargets.resolve(PickKind.AREA, W, WorldTargets.NONE, PickFaces.ANY, true).reason());
        assertEquals(WorldTargets.WORLD, WorldTargets.resolve(PickKind.AREA, W, hit, PickFaces.ANY, false).reason());
    }

    @Test void givenATypedOrStandingCell_whenResolvedForAnArea_thenAnyCellCounts_alsoAir() {
        WorldTargets.Resolution r = WorldTargets.areaCell(W, at(1, 200, 1), true);
        assertTrue(r.ok());
        assertEquals(at(1, 200, 1), ((Picked.Area) r.target()).min());
        assertEquals(WorldTargets.WORLD, WorldTargets.areaCell(OTHER, at(1, 2, 3), false).reason());
    }

    // ------------------------------------------------------------------ end rules of the runtime

    @Test void givenEveryCause_whenTheEndRuleIsAsked_thenOnlyWhatThePlayerAskedForGoesBack() {
        for (WorldPickEnds.Cause c : WorldPickEnds.Cause.values()) {
            WorldPickEnds.Rule r = WorldPickEnds.rule(c);
            assertNotNull(r, c.name());
            if (r.end() == null) {
                assertFalse(r.goBack(), c.name());
                assertFalse(r.openAgain(), c.name());
                continue;
            }
            assertEquals(PickerRuntime.returns(r.end()) && c != WorldPickEnds.Cause.NEW_PICK_FROM_HANDLER, r.goBack(), c.name());
            assertFalse(r.goBack() && r.openAgain(), c + " both goes back and offers Open again");
        }
    }

    @Test void givenTheDesignTable_whenEachCauseIsAsked_thenTheEndAndTheChatLineMatch() {
        assertRule(WorldPickEnds.Cause.CHOSEN, Vystorm.vystorm_core.picker.PickEnd.DONE, false, true);
        assertRule(WorldPickEnds.Cause.HANDLER_FAILED, Vystorm.vystorm_core.picker.PickEnd.DONE, false, true);
        assertRule(WorldPickEnds.Cause.CANCEL_BUTTON, Vystorm.vystorm_core.picker.PickEnd.BACK, false, true);
        assertRule(WorldPickEnds.Cause.TIMEOUT, Vystorm.vystorm_core.picker.PickEnd.TIMEOUT, true, false);
        assertRule(WorldPickEnds.Cause.DEATH, Vystorm.vystorm_core.picker.PickEnd.INTERRUPTED, true, false);
        assertRule(WorldPickEnds.Cause.WORLD_CHANGE, Vystorm.vystorm_core.picker.PickEnd.WORLD_CHANGED, true, false);
        assertRule(WorldPickEnds.Cause.OTHER_MENU, Vystorm.vystorm_core.picker.PickEnd.REPLACED, true, false);
        assertRule(WorldPickEnds.Cause.NEW_PICK, Vystorm.vystorm_core.picker.PickEnd.REPLACED, false, false);
        assertRule(WorldPickEnds.Cause.NEW_PICK_FROM_HANDLER, Vystorm.vystorm_core.picker.PickEnd.DONE, false, false);
        assertRule(WorldPickEnds.Cause.QUIT, Vystorm.vystorm_core.picker.PickEnd.QUIT, false, false);
        assertRule(WorldPickEnds.Cause.OWNER_DISABLED, Vystorm.vystorm_core.picker.PickEnd.OWNER_DISABLED, false, false);
        assertRule(WorldPickEnds.Cause.PLUGIN_CANCEL, Vystorm.vystorm_core.picker.PickEnd.CANCELLED_BY_PLUGIN, false, false);
        // damage and a teleport within the world keep the pick (design 2.3, 4.7)
        assertNull(WorldPickEnds.rule(WorldPickEnds.Cause.DAMAGE).end());
        assertNull(WorldPickEnds.rule(WorldPickEnds.Cause.TELEPORT_SAME_WORLD).end());
        // closing the confirm view keeps the pick and both corners
        assertNull(WorldPickEnds.rule(WorldPickEnds.Cause.CONFIRM_CLOSED).end());
    }

    private static void assertRule(WorldPickEnds.Cause c, Vystorm.vystorm_core.picker.PickEnd end, boolean openAgain, boolean back) {
        WorldPickEnds.Rule r = WorldPickEnds.rule(c);
        assertEquals(end, r.end(), c.name());
        assertEquals(openAgain, r.openAgain(), c.name());
        assertEquals(back, r.goBack(), c.name());
    }
}
