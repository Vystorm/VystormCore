package Vystorm.vystorm_core.picker;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.region.RegionShape;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The public area pick types (0.71.0): {@link Picked.Area}, {@link AreaRules}, {@link AreaHeight}, the capability. */
class AreaPickTest {
    private static final UUID WORLD = UUID.randomUUID();

    @Test void givenCornersInAnyOrder_whenAreaBuilt_thenMinAndMaxAreSorted_andSizesCountBothEnds() {
        Picked.Area a = new Picked.Area(WORLD, new BlockPosition(10, 70, -3), new BlockPosition(-1, 66, 5));
        assertEquals(new BlockPosition(-1, 66, -3), a.min());
        assertEquals(new BlockPosition(10, 70, 5), a.max());
        assertEquals(a.min(), a.cell());
        assertEquals(12, a.sizeX());
        assertEquals(5, a.sizeY());
        assertEquals(9, a.sizeZ());
        assertEquals(540L, a.volume());
    }

    @Test void givenOneCell_whenAreaBuilt_thenSizeOneAndVolumeOne() {
        Picked.Area a = new Picked.Area(WORLD, new BlockPosition(3, 3, 3), new BlockPosition(3, 3, 3));
        assertEquals(1, a.sizeX());
        assertEquals(1L, a.volume());
    }

    @Test void givenTheLargestArea_whenVolumeAsked_thenNoIntOverflow() {
        Picked.Area a = new Picked.Area(WORLD, new BlockPosition(-500_000, -2032, -500_000), new BlockPosition(499_999, 2031, 499_999));
        assertEquals(1_000_000L * 4064L * 1_000_000L, a.volume());
    }

    @Test void givenArea_whenContainsAndShapeAsked_thenBothEndsInclusive() {
        Picked.Area a = new Picked.Area(WORLD, new BlockPosition(0, 0, 0), new BlockPosition(2, 2, 2));
        assertTrue(a.contains(0, 0, 0));
        assertTrue(a.contains(2, 2, 2));
        assertFalse(a.contains(3, 2, 2));
        assertFalse(a.contains(1, -1, 1));
        RegionShape.Box box = a.shape();
        assertEquals(WORLD, box.world());
        assertEquals(0, box.minX());
        assertEquals(2, box.maxY());
    }

    @Test void givenNoRules_whenDefaults_thenNoOwnLimitsAndPickedHeight() {
        AreaRules r = AreaRules.DEFAULT;
        assertEquals(AreaHeight.PICKED, r.height());
        assertEquals(1, r.minX());
        assertEquals(1, r.minY());
        assertEquals(1, r.minZ());
        assertEquals(Integer.MAX_VALUE, r.maxX());
        assertEquals(Long.MAX_VALUE, r.maxVolume());
        assertEquals(0, r.fixedHeight());
        assertSame(AreaRules.DEFAULT, WorldPick.builder("t").onPick((v, c) -> Result.ok()).build().area());
    }

    @Test void givenRules_whenBuilt_thenKept() {
        AreaRules r = AreaRules.builder().minSize(2, 3, 4).maxSize(64, 128, 64).maxVolume(200_000).height(AreaHeight.ASK).build();
        assertEquals(2, r.minX());
        assertEquals(3, r.minY());
        assertEquals(4, r.minZ());
        assertEquals(64, r.maxX());
        assertEquals(128, r.maxY());
        assertEquals(64, r.maxZ());
        assertEquals(200_000L, r.maxVolume());
        assertEquals(AreaHeight.ASK, r.height());
        WorldPick pick = WorldPick.builder("t").target(PickKind.AREA).area(r).onPick((v, c) -> Result.ok()).build();
        assertEquals(PickKind.AREA, pick.kind());
        assertSame(r, pick.area());
    }

    @Test void givenFixedHeight_whenBuilt_thenModeFixedWithTheHeight_andOutsideTheYLimitsRefused() {
        AreaRules r = AreaRules.builder().fixedHeight(10).build();
        assertEquals(AreaHeight.FIXED, r.height());
        assertEquals(10, r.fixedHeight());
        assertThrows(IllegalArgumentException.class, () -> AreaRules.builder().height(AreaHeight.FIXED));
        assertThrows(IllegalArgumentException.class, () -> AreaRules.builder().fixedHeight(0));
        assertThrows(IllegalStateException.class, () -> AreaRules.builder().maxSize(10, 5, 10).fixedHeight(6).build());
        assertThrows(IllegalStateException.class, () -> AreaRules.builder().minSize(1, 8, 1).fixedHeight(6).build());
        // another mode after a fixed height drops the number
        assertEquals(0, AreaRules.builder().fixedHeight(6).height(AreaHeight.FULL).build().fixedHeight());
    }

    @Test void givenOddLimits_whenBuilt_thenRefused() {
        assertThrows(IllegalArgumentException.class, () -> AreaRules.builder().minSize(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AreaRules.builder().maxSize(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> AreaRules.builder().maxVolume(0));
        assertThrows(IllegalStateException.class, () -> AreaRules.builder().minSize(5, 1, 1).maxSize(4, 9, 9).build());
        assertThrows(IllegalStateException.class, () -> AreaRules.builder().minSize(10, 10, 10).maxVolume(999).build());
        assertThrows(NullPointerException.class, () -> AreaRules.builder().height(null));
    }

    @Test void givenAreaKind_whenAsked_thenAWorldPick() {
        assertTrue(PickKind.AREA.world());
    }

    @Test void givenCore_whenCapabilityAsked_thenWorldPickAreaSince071() {
        assertTrue(CoreApi.has(CoreApi.Capability.WORLD_PICK_AREA));
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.WORLD_PICK_AREA));
    }
}
