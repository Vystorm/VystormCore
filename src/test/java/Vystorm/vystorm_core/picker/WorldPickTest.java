package Vystorm.vystorm_core.picker;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

/** The public world pick types (0.71.0): builder rules, value records, capability. */
class WorldPickTest {
    private static final UUID WORLD = UUID.randomUUID();

    private static WorldPick.Builder builder() { return WorldPick.builder("Choose").onPick((v, c) -> Result.ok()); }

    @Test void givenNoSettings_whenBuilt_thenBlockPickWithDefaultReachAnyFaceNoProtection() {
        WorldPick pick = builder().build();
        assertEquals(PickKind.BLOCK, pick.kind());
        assertEquals(WorldPick.DEFAULT_REACH, pick.reach());
        assertEquals(PickFaces.ANY, pick.faces());
        assertEquals(PickProtection.NONE, pick.protection());
        assertEquals(WorldPick.DEFAULT_TIMEOUT, pick.timeout());
        assertTrue(pick.entities().isEmpty());
        assertNull(pick.bypass());
        assertNull(pick.outline());
        assertTrue(pick.check().check(null, null).allowed());
    }

    @Test void givenOutOfRangeValues_whenBuilt_thenReachAndTimeoutAreCappedOrRefused() {
        assertEquals(WorldPick.MAX_REACH, builder().reach(500).build().reach());
        assertThrows(IllegalArgumentException.class, () -> builder().reach(0));
        assertEquals(Duration.ofSeconds(10), builder().timeout(Duration.ofSeconds(1)).build().timeout());
        assertEquals(WorldPick.MAX_TIMEOUT, builder().timeout(Duration.ofHours(1)).build().timeout());
    }

    @Test void givenPlayersKindOrOddFaces_whenBuilt_thenRefused() {
        assertThrows(IllegalArgumentException.class, () -> builder().target(PickKind.PLAYERS));
        assertThrows(IllegalArgumentException.class, () -> builder().faces(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> builder().faces(Set.of(BlockFace.NORTH_EAST)));
        assertThrows(IllegalStateException.class, () -> WorldPick.builder("x").build());
        assertEquals(PickFaces.SIDES, builder().target(PickKind.FACE).faces(PickFaces.SIDES).build().faces());
    }

    @Test void givenEveryWorldKind_whenAsked_thenItIsAWorldPick() {
        for (PickKind k : PickKind.values()) assertEquals(k != PickKind.PLAYERS, k.world(), k.name());
    }

    @Test void givenBoxCornersInAnyOrder_whenBuilt_thenMinAndMaxAreSorted() {
        WorldPick.Box box = new WorldPick.Box(new Point3(3, 1, -2), new Point3(1, 5, -4));
        assertEquals(new Point3(1, 1, -4), box.min());
        assertEquals(new Point3(3, 5, -2), box.max());
    }

    @Test void givenTargets_whenAskedForTheirCell_thenTheContainingBlock() {
        assertEquals(new BlockPosition(-1, 64, 2), new Picked.Position(WORLD, new Point3(-0.5, 64.2, 2.9), 0, 0, BlockFace.SOUTH).cell());
        assertEquals(new BlockPosition(1, 2, 3), new Picked.Block(WORLD, new BlockPosition(1, 2, 3), "stone").cell());
        Picked.Face face = new Picked.Face(WORLD, new BlockPosition(1, 2, 3), BlockFace.WEST, "stone");
        assertEquals(new BlockPosition(1, 2, 3), face.cell());
        assertEquals(new BlockPosition(0, 2, 3), face.front());
        assertEquals(new BlockPosition(4, 70, -9), new Picked.EntityHit(WORLD, UUID.randomUUID(), "painting", new Point3(4.5, 70.5, -8.5), BlockFace.NORTH).cell());
    }

    @Test void givenCandidateWithNullCorner_whenBuilt_thenCornerIsEmpty() {
        PickCandidate c = new PickCandidate(new Picked.Block(WORLD, new BlockPosition(0, 0, 0), "stone"), null, new TestSession());
        assertEquals(Optional.empty(), c.firstCorner());
    }

    @Test void givenCore_whenCapabilityAsked_thenWorldPickSince071() {
        assertTrue(CoreApi.has(CoreApi.Capability.WORLD_PICK));
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.WORLD_PICK));
    }

    static final class TestSession implements Vystorm.vystorm_core.panel.PanelSession {
        private final java.util.Map<String, Object> values = new java.util.HashMap<>();
        @Override public int id() { return 1; }
        @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) values.get(key); }
        @Override public void put(String key, Object value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
    }
}
