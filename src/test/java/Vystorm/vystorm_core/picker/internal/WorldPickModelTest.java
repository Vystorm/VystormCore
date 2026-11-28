package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.picker.PickFaces;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.WorldPick;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.List;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The pure parts of the world pick (0.71.0): config, target rules, outline, typed coordinates, protection, timing. */
class WorldPickModelTest {
    private static final UUID W = UUID.randomUUID();
    private static final BlockPosition AT = new BlockPosition(10, 64, -3);

    private static WorldTargets.Hit.OnBlock block(BlockFace face, boolean accepted, boolean container) {
        return new WorldTargets.Hit.OnBlock(AT, face, "chest", accepted, container);
    }

    private static WorldTargets.Hit.OnEntity entity(boolean accepted) {
        return new WorldTargets.Hit.OnEntity(UUID.randomUUID(), "painting", new Point3(1.5, 65, 2.03), BlockFace.SOUTH, new Point3(1, 64.5, 2), new Point3(2, 65.5, 2.06), accepted);
    }

    // ------------------------------------------------------------------ config

    @Test void givenNoSection_whenRead_thenDefaultsOfTheDesign() {
        WorldPickConfig c = WorldPickConfig.read(new YamlConfiguration());
        assertEquals(new WorldPickConfig(true, 6, 64, 48, 450, 128, AreaCaps.HARD), c);
    }

    @Test void givenValuesBeyondTheHardCaps_whenRead_thenClamped() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pickers.world.max-reach", 500);
        y.set("pickers.world.default-reach", 300);
        y.set("pickers.world.preview-distance", 1000);
        y.set("pickers.world.preview-points", 5000);
        y.set("pickers.world.enabled", false);
        WorldPickConfig c = WorldPickConfig.read(y);
        assertEquals(64, c.maxReach());
        assertEquals(64, c.defaultReach());
        assertEquals(64, c.previewDistance());
        assertEquals(512, c.previewPoints());
        assertFalse(c.enabled());
    }

    @Test void givenLowerMaxReach_whenPickAsksMore_thenCapped_andDefaultReachFollowsConfig() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pickers.world.max-reach", 20);
        y.set("pickers.world.default-reach", 8);
        WorldPickConfig c = WorldPickConfig.read(y);
        WorldPick.Builder b = WorldPick.builder("t").onPick((v, p) -> Result.ok());
        assertEquals(8, c.reach(b.build()));
        assertEquals(20, c.reach(b.reach(40).build()));
        assertEquals(3, c.reach(b.reach(3).build()));
    }

    // ------------------------------------------------------------------ targets

    @Test void givenBlockHit_whenResolvedPerKind_thenKindRulesApply() {
        assertInstanceOf(Picked.Block.class, WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, true, false), PickFaces.ANY, true).target());
        assertEquals(WorldTargets.BLOCK, WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, false, false), PickFaces.ANY, true).reason());
        WorldTargets.Resolution face = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.NORTH, true, false), PickFaces.SIDES, true);
        assertEquals(new Picked.Face(W, AT, BlockFace.NORTH, "chest"), face.target());
        WorldTargets.Resolution top = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.UP, true, false), PickFaces.SIDES, true);
        assertFalse(top.ok());
        assertEquals(WorldTargets.FACE, top.reason());
        assertNotNull(top.hit(), "the refused face is still outlined (red)");
        assertEquals(new Picked.Container(W, AT, "chest"), WorldTargets.resolve(PickKind.CONTAINER, W, block(BlockFace.UP, true, true), PickFaces.ANY, true).target());
        assertEquals(WorldTargets.CONTAINER, WorldTargets.resolve(PickKind.CONTAINER, W, block(BlockFace.UP, true, false), PickFaces.ANY, true).reason());
        assertEquals(WorldTargets.BLOCK, WorldTargets.resolve(PickKind.CONTAINER, W, block(BlockFace.UP, false, true), PickFaces.ANY, true).reason());
    }

    @Test void givenEntityOrNothing_whenResolved_thenEntityRulesAndNothingReason() {
        WorldTargets.Hit.OnEntity e = entity(true);
        Picked.EntityHit hit = (Picked.EntityHit) WorldTargets.resolve(PickKind.ENTITY, W, e, PickFaces.ANY, true).target();
        assertEquals(e.id(), hit.entity());
        assertEquals("painting", hit.entityType());
        assertEquals(BlockFace.SOUTH, hit.facing());
        assertEquals(WorldTargets.ENTITY, WorldTargets.resolve(PickKind.ENTITY, W, entity(false), PickFaces.ANY, true).reason());
        assertEquals(WorldTargets.NOTHING, WorldTargets.resolve(PickKind.ENTITY, W, block(BlockFace.UP, true, false), PickFaces.ANY, true).reason());
        assertEquals(WorldTargets.NOTHING, WorldTargets.resolve(PickKind.BLOCK, W, WorldTargets.NONE, PickFaces.ANY, true).reason());
        assertEquals(WorldTargets.NOTHING, WorldTargets.resolve(PickKind.BLOCK, W, e, PickFaces.ANY, true).reason());
    }

    @Test void givenWorldNotAllowed_whenResolved_thenWorldReasonForEveryKind() {
        assertEquals(WorldTargets.WORLD, WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, true, false), PickFaces.ANY, false).reason());
        assertEquals(WorldTargets.WORLD, WorldTargets.position(W, new Point3(0, 64, 0), 0, 0, false).reason());
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.resolve(PickKind.POSITION, W, WorldTargets.NONE, PickFaces.ANY, true));
    }

    @Test void givenYaw_whenRounded_thenTheSideThePlayerFaces() {
        assertEquals(BlockFace.SOUTH, WorldTargets.facing(0));
        assertEquals(BlockFace.SOUTH, WorldTargets.facing(44));
        assertEquals(BlockFace.WEST, WorldTargets.facing(46));
        assertEquals(BlockFace.NORTH, WorldTargets.facing(180));
        assertEquals(BlockFace.NORTH, WorldTargets.facing(-180));
        assertEquals(BlockFace.EAST, WorldTargets.facing(-90));
        assertEquals(BlockFace.EAST, WorldTargets.facing(270));
        assertEquals(BlockFace.SOUTH, WorldTargets.facing(360));
        Picked.Position p = (Picked.Position) WorldTargets.position(W, new Point3(0.5, 64, 0.5), 91, 10, true).target();
        assertEquals(BlockFace.WEST, p.facing());
        assertEquals(10f, p.pitch());
    }

    @Test void givenEyeBesideABlock_whenFaceTowards_thenTheFaceThatLooksAtTheEye() {
        assertEquals(BlockFace.NORTH, WorldTargets.faceTowards(AT, new Point3(10.5, 65, -8)));
        assertEquals(BlockFace.EAST, WorldTargets.faceTowards(AT, new Point3(14, 65, -2.5)));
        assertEquals(BlockFace.UP, WorldTargets.faceTowards(AT, new Point3(10.5, 70, -2.5)));
    }

    @Test void givenTargets_whenAskedForTheEditCell_thenFrontOfAFaceElseTheCell() {
        assertEquals(new BlockPosition(10, 64, -4), WorldTargets.editCell(new Picked.Face(W, AT, BlockFace.NORTH, "stone")));
        assertEquals(AT, WorldTargets.editCell(new Picked.Block(W, AT, "stone")));
    }

    @Test void givenSameAimTwice_whenKeyed_thenSameKey_andAnotherFaceGivesANewKey() {
        WorldTargets.Resolution a = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.NORTH, true, false), PickFaces.ANY, true);
        WorldTargets.Resolution b = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.NORTH, true, false), PickFaces.ANY, true);
        WorldTargets.Resolution c = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.EAST, true, false), PickFaces.ANY, true);
        assertEquals(WorldTargets.key(a), WorldTargets.key(b));
        assertNotEquals(WorldTargets.key(a), WorldTargets.key(c));
    }

    // ------------------------------------------------------------------ outline

    @Test void givenBlockTarget_whenOutlined_thenTwelveEdgesAroundTheBlock() {
        WorldTargets.Resolution r = WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, true, false), PickFaces.ANY, true);
        List<Point3> points = PickOutline.points(PickKind.BLOCK, r, List.of(), new Point3(10, 66, -6), 48, 450);
        assertEquals(60, points.size());
        for (Point3 p : points) assertTrue(p.x() >= 9.9 && p.x() <= 11.1 && p.y() >= 63.9 && p.y() <= 65.1 && p.z() >= -3.1 && p.z() <= -1.9, "" + p);
    }

    @Test void givenFaceTarget_whenOutlined_thenSquareJustInFrontOfThatFace() {
        WorldTargets.Resolution r = WorldTargets.resolve(PickKind.FACE, W, block(BlockFace.NORTH, true, false), PickFaces.ANY, true);
        List<Point3> points = PickOutline.points(PickKind.FACE, r, null, new Point3(10, 66, -6), 48, 450);
        assertFalse(points.isEmpty());
        for (Point3 p : points) assertEquals(-3.03, p.z(), 1e-9);
    }

    @Test void givenPositionTarget_whenOutlined_thenFeetSquareAndALineInTheFacingDirection() {
        WorldTargets.Resolution r = WorldTargets.position(W, new Point3(0.5, 64, 0.5), 180, 0, true);
        List<Point3> points = PickOutline.points(PickKind.POSITION, r, List.of(), new Point3(0.5, 65.6, 0.5), 48, 450);
        assertEquals(24 + 7, points.size());
        assertTrue(points.stream().anyMatch(p -> p.distanceSquared(new Point3(0.5, 64.05, -1.0)) < 1e-12), "the facing line ends 1.5 north of the centre");
    }

    @Test void givenBigExtraBoxesAndFarPoints_whenOutlined_thenCappedAndClippedByDistance() {
        WorldTargets.Resolution r = WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, true, false), PickFaces.ANY, true);
        List<WorldPick.Box> extra = List.of(new WorldPick.Box(new Point3(-40, 60, -40), new Point3(40, 90, 40)), new WorldPick.Box(new Point3(500, 60, 500), new Point3(510, 70, 510)));
        Point3 eye = new Point3(10, 66, -6);
        List<Point3> points = PickOutline.points(PickKind.BLOCK, r, extra, eye, 48, 450);
        assertTrue(points.size() <= 450, "at most the pass limit: " + points.size());
        assertTrue(points.size() > 60, "the plugin's box is drawn too");
        for (Point3 p : points) assertTrue(p.distanceSquared(eye) <= 48 * 48, "within the preview distance: " + p);
        assertEquals(PickOutline.points(PickKind.BLOCK, r, List.of(), eye, 48, 450), points.subList(0, 60), "the target comes first");
    }

    @Test void givenNothingAimed_whenOutlined_thenNoPoints() {
        WorldTargets.Resolution r = WorldTargets.resolve(PickKind.BLOCK, W, WorldTargets.NONE, PickFaces.ANY, true);
        assertTrue(PickOutline.points(PickKind.BLOCK, r, List.of(), new Point3(0, 0, 0), 48, 450).isEmpty());
    }

    // ------------------------------------------------------------------ typed coordinates

    @Test void givenTypedCoordinates_whenParsed_thenAbsoluteRelativeAndFaces() {
        Point3 origin = new Point3(100.5, 64, -20.5);
        assertEquals(new Point3(1, 2, 3), CoordinateInput.parse("1 2 3", origin, false).point());
        assertEquals(new Point3(1, 2.5, -3), CoordinateInput.parse(" 1, 2.5 ,-3 ", origin, false).point());
        assertEquals(new Point3(100.5, 66, -20.5), CoordinateInput.parse("~ ~2 ~", origin, false).point());
        CoordinateInput.Parsed f = CoordinateInput.parse("1 2 3 north", origin, true);
        assertEquals(BlockFace.NORTH, f.face());
        assertNull(CoordinateInput.parse("1 2 3", origin, true).face());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("1 2 3 north", origin, false).error());
        assertEquals(CoordinateInput.FACE, CoordinateInput.parse("1 2 3 sideways", origin, true).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("1 2", origin, false).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("a b c", origin, false).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("", origin, false).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse(null, origin, false).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("1 NaN 3", origin, false).error());
        assertEquals(CoordinateInput.FORMAT, CoordinateInput.parse("1 2 99999999999", origin, false).error());
        assertEquals("1 -2 3", CoordinateInput.text(1, -2, 3));
    }

    // ------------------------------------------------------------------ protection

    @Test void givenEditVerdicts_whenCombined_thenAllowDenyPartialAndBypass() {
        assertNull(PickProtectionCheck.denied(new EditVerdict.Allow(), AT, false));
        assertEquals("regions", PickProtectionCheck.denied(new EditVerdict.Deny("regions"), AT, false));
        assertEquals("unknown", PickProtectionCheck.denied(new EditVerdict.Deny(""), AT, false));
        assertNull(PickProtectionCheck.denied(new EditVerdict.Partial((x, y, z) -> x == 10 && y == 64 && z == -3), AT, false));
        assertEquals("partial", PickProtectionCheck.denied(new EditVerdict.Partial((x, y, z) -> false), AT, false));
        assertEquals("guard-failed", PickProtectionCheck.denied(new EditVerdict.Partial((x, y, z) -> { throw new IllegalStateException(); }), AT, false));
        assertNull(PickProtectionCheck.denied(new EditVerdict.Deny("regions"), AT, true), "bypass skips it");
        assertEquals("unknown", PickProtectionCheck.denied(null, AT, false));
    }

    // ------------------------------------------------------------------ timing

    @Test void givenStart_whenClickedDuringGraceThenAfter_thenOnlyTheLaterClickCounts() {
        WorldPickState s = new WorldPickState(1000, 120_000);
        assertFalse(s.trigger(1200, false), "the click that opened the pick");
        assertTrue(s.trigger(1600, false));
    }

    @Test void givenOnePhysicalClick_whenInteractSwingAndHitArrive_thenOnlyTheFirstCounts() {
        WorldPickState s = new WorldPickState(0, 120_000);
        assertTrue(s.trigger(1000, false));
        assertFalse(s.trigger(1000, true));
        assertFalse(s.trigger(1050, false));
        assertTrue(s.trigger(1250, true), "the next click a moment later");
    }

    @Test void givenDroppedItem_whenTheSwingFollows_thenItIsNoClick() {
        WorldPickState s = new WorldPickState(0, 120_000);
        s.dropped(2000);
        assertFalse(s.trigger(2050, true));
        assertTrue(s.trigger(2050, false), "a real interact still counts");
    }

    @Test void givenOptions_whenOpenAndClosed_thenNoClicksMeanwhile_andTheClosingSneakDoesNotReopen() {
        WorldPickState s = new WorldPickState(0, 120_000);
        assertTrue(s.sneak(1000));
        s.openOptions(1000);
        assertEquals(WorldPickState.Phase.OPTIONS, s.phase());
        assertFalse(s.trigger(2000, false));
        assertFalse(s.sneak(2000));
        s.resume(3000);
        assertEquals(WorldPickState.Phase.CHOOSING, s.phase());
        assertFalse(s.sneak(3200));
        assertFalse(s.trigger(3200, false));
        assertTrue(s.sneak(3600));
        assertTrue(s.trigger(3600, false));
    }

    @Test void givenIdle_whenTimeoutPasses_thenTimedOut_andOptionsGiveThreeTimesAsLong() {
        WorldPickState s = new WorldPickState(0, 10_000);
        assertFalse(s.timedOut(10_000));
        assertTrue(s.timedOut(10_001));
        assertEquals(0.5, s.left(5_000), 1e-9);
        WorldPickState o = new WorldPickState(0, 10_000);
        o.prompt(0);
        assertFalse(o.timedOut(29_000));
        assertTrue(o.timedOut(30_001));
    }

    @Test void givenAim_whenTargetChanges_thenVerdictCacheIsDroppedAndActivityCounts() {
        WorldPickState s = new WorldPickState(0, 10_000);
        assertTrue(s.aim("a", 5_000));
        s.cache(PickVerdict.ok());
        assertFalse(s.aim("a", 6_000));
        assertNotNull(s.cached());
        assertTrue(s.aim("b", 9_000));
        assertNull(s.cached());
        assertFalse(s.timedOut(18_000), "the change of target at 9 s counted");
    }

    @Test void givenRefusal_whenHeld_thenShownForThreeSeconds() {
        WorldPickState s = new WorldPickState(0, 10_000);
        s.hold("no", 1000);
        assertEquals("no", s.held(3999));
        assertNull(s.held(4000));
        assertNull(s.heldTarget(2000), "a click refusal outlines nothing of its own");
    }

    /** Defect 4 of the first client look: a refused typed coordinate showed for a moment only, the outline stayed green. */
    @Test void givenRefusedTypedCoordinate_whenHeld_thenReasonAndRefusedCellStaySixSeconds() {
        WorldPickState s = new WorldPickState(0, 120_000);
        s.resume(1000);   // the prompt closed
        Object cell = "cell 10 64 -3";
        s.hold("This block cannot be chosen.", 1000, WorldPickState.HOLD_TYPED, cell);
        assertEquals("This block cannot be chosen.", s.held(1000 + 2500), "still shown where the old 3 s hold was nearly over");
        assertEquals(cell, s.heldTarget(1000 + 5999));
        assertEquals("This block cannot be chosen.", s.held(1000 + 5999));
        assertNull(s.held(1000 + 6000));
        assertNull(s.heldTarget(1000 + 6000), "then the outline follows the aim again");
        assertTrue(WorldPickState.HOLD_TYPED > WorldPickState.HOLD);
        s.hold("next", 8000);
        assertNull(s.heldTarget(8001), "a later plain refusal drops the typed cell");
    }

    /** Found in the client check of the fix: a refused resolution has no target, so its cell must come from the hit. */
    @Test void givenARefusedTypedBlock_whenOutlined_thenTheTwelveEdgesOfThatCellFromTheHit() {
        WorldTargets.Resolution refused = WorldTargets.resolve(PickKind.BLOCK, W, block(BlockFace.UP, false, false), PickFaces.ANY, true);
        assertFalse(refused.ok());
        assertNull(refused.target(), "the cause: no target to key on");
        WorldPickConfig cfg = WorldPickConfig.read(new YamlConfiguration());
        java.util.List<Point3> points = WorldPickRuntime.refusedOutline(PickKind.BLOCK, refused, new Point3(10, 66, 0), cfg);
        assertEquals(PickOutline.target(PickKind.BLOCK, refused).size(), points.size());
        assertFalse(points.isEmpty());
        for (Point3 p : points) assertTrue(p.x() > 9.9 && p.x() < 11.1 && p.y() > 63.9 && p.y() < 65.1 && p.z() > -3.1 && p.z() < -1.9, p.toString());
        assertTrue(WorldPickRuntime.refusedOutline(PickKind.BLOCK, null, new Point3(10, 66, 0), cfg).isEmpty());
        assertTrue(WorldPickRuntime.refusedOutline(PickKind.BLOCK, WorldTargets.resolve(PickKind.BLOCK, W, WorldTargets.NONE, PickFaces.ANY, true), new Point3(10, 66, 0), cfg).isEmpty(),
                "unparsable or far coordinates name no cell: the preview follows the aim");
    }

    @Test void givenTypedText_whenRepeatedInChat_thenNoTagsShortAndNeverEmpty() {
        assertEquals("1 2 3", WorldPickRuntime.shown(" 1 2 3 "));
        assertEquals("b", WorldPickRuntime.shown("<b>"));
        assertEquals("?", WorldPickRuntime.shown(""));
        assertEquals("?", WorldPickRuntime.shown(null));
        assertEquals(32, WorldPickRuntime.shown("x".repeat(80)).length());
    }
}
