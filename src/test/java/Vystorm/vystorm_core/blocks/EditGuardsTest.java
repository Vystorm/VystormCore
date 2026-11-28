package Vystorm.vystorm_core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface;
import Vystorm.vystorm_core.blocks.internal.EditGuardRegistry;
import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EditGuardsTest {
    private final UUID worldId = UUID.randomUUID();
    private final World world = mock(World.class);
    private final Plugin owner = mock(Plugin.class), editor = mock(Plugin.class), guardOwner = mock(Plugin.class);
    private final List<String> logged = new ArrayList<>();
    private final EditGuardRegistry guards = new EditGuardRegistry(RegionService.get(), (message, failure) -> logged.add(message));

    EditGuardsTest() {
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("edit-guards-test");
        when(owner.getName()).thenReturn("EditGuardsTestRegions");
        when(editor.getName()).thenReturn("EditPlugin");
        when(guardOwner.getName()).thenReturn("GuardPlugin");
    }

    @AfterEach
    void cleanUp() { RegionService.get().unregisterOwner(owner); }

    /** Chunk 2/3, y 0..15. */
    private EditAsk ask() { return new EditAsk(editor, UUID.randomUUID(), world, 2, 3, 0, 15); }

    private void region(String id, int minX, int minZ, int maxX, int maxZ, Boolean build) {
        Region region = Region.of("edittest", id, new RegionShape.Box(worldId, minX, -64, minZ, maxX, 320, maxZ));
        if (build != null) region = region.withFlags(Map.of("build", build));
        RegionService.get().register(owner, region);
    }

    @Test
    void givenNoRegion_whenAsked_thenAllow() {
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
    }

    @Test
    void givenRegionWithoutTheBuildFlag_whenAsked_thenAllow() {
        region("plain", 0, 0, 100, 100, null);
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
    }

    @Test
    void givenRegionDenyingBuildOverTheWholeChunk_whenAsked_thenDeny() {
        region("spawn", 0, 0, 100, 100, false);
        EditVerdict verdict = guards.ask(ask());
        EditVerdict.Deny deny = assertInstanceOf(EditVerdict.Deny.class, verdict);
        assertEquals("regions", deny.reasonKey());
    }

    @Test
    void givenRegionDenyingBuildOverHalfTheChunk_whenAsked_thenPartialWithCellsInsideDenied() {
        // chunk 2/3 is x 32..47, z 48..63; the region covers x 32..39
        region("half", 0, 0, 39, 100, false);
        EditVerdict.Partial partial = assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask()));
        CellTest allowed = partial.allowed();
        int[][] inside = {{32, 0, 48}, {39, 15, 63}, {35, 7, 50}, {33, 3, 60}};
        int[][] outside = {{40, 0, 48}, {47, 15, 63}, {44, 7, 55}, {41, 3, 49}};
        for (int[] c : inside) assertFalse(allowed.allowed(c[0], c[1], c[2]), "inside " + c[0] + "," + c[2]);
        for (int[] c : outside) assertTrue(allowed.allowed(c[0], c[1], c[2]), "outside " + c[0] + "," + c[2]);
    }

    @Test
    void givenDenyingRegionAndAHigherAllowingRegion_whenAsked_thenPartialAndTheAllowedPartIsOpen() {
        region("town", 0, 0, 100, 100, false);
        Region plot = Region.of("edittest", "plot", new RegionShape.Box(worldId, 32, -64, 48, 35, 320, 51))
                .withPriority(500).withFlags(Map.of("build", true));
        RegionService.get().register(owner, plot);
        EditVerdict.Partial partial = assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask()));
        assertTrue(partial.allowed().allowed(33, 5, 49));
        assertFalse(partial.allowed().allowed(40, 5, 60));
    }

    @Test
    void givenGuardThatDenies_whenCoreAllows_thenTheGuardWins() throws Exception {
        try (AutoCloseable registration = guards.register(guardOwner, ask -> new EditVerdict.Deny("claims"))) {
            EditVerdict.Deny deny = assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
            assertEquals("claims", deny.reasonKey());
        }
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()), "close() unregistered the guard");
    }

    @Test
    void givenTwoPartials_whenCombined_thenACellIsAllowedOnlyWhenBothAllowIt() throws Exception {
        region("west", 0, 0, 39, 100, false);                               // denies x < 40
        try (AutoCloseable registration = guards.register(guardOwner, ask -> new EditVerdict.Partial((x, y, z) -> z >= 56))) {
            EditVerdict.Partial partial = assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask()));
            assertTrue(partial.allowed().allowed(44, 0, 60));
            assertFalse(partial.allowed().allowed(44, 0, 50), "guard denies z < 56");
            assertFalse(partial.allowed().allowed(36, 0, 60), "region denies x < 40");
        }
    }

    @Test
    void givenGuardThatThrows_whenAskedTwice_thenDenyBothTimesAndLoggedOnce() throws Exception {
        try (AutoCloseable registration = guards.register(guardOwner, ask -> { throw new IllegalStateException("broken claims"); })) {
            assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
            assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
        }
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("GuardPlugin"), logged.get(0));
    }

    @Test
    void givenGuardOwnerDisabled_whenReleased_thenItsGuardsAreGone() {
        guards.register(guardOwner, ask -> new EditVerdict.Deny("claims"));
        guards.release(guardOwner);
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
    }

    @Test
    void givenAreaManager_whenAsked_thenItUsesTheSharedGuards() {
        AreaInterface area = Mockito.mock(AreaInterface.class, Mockito.CALLS_REAL_METHODS);
        assertSame(EditGuards.instance(), area.editGuards());
        assertInstanceOf(EditVerdict.Allow.class, area.mayEdit(new EditAsk(editor, null, world, 900, 900, 0, 15)));
    }

    // ---- own-regions guards (0.71.0) ------------------------------------------------------------------------------

    private final Plugin other = otherPlugin();

    private static Plugin otherPlugin() {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn("OtherRegionsPlugin");
        return p;
    }

    @AfterEach
    void cleanUpOther() { RegionService.get().unregisterOwner(other); }

    private void otherRegion(String id, int minX, int minZ, int maxX, int maxZ, boolean build) {
        RegionService.get().register(other, Region.of("edittest", id, new RegionShape.Box(worldId, minX, -64, minZ, maxX, 320, maxZ))
                .withFlags(Map.of("build", build)));
    }

    @Test
    void givenOwnRegionsGuardThatAllows_whenItsOwnRegionDeniesBuild_thenAllow() throws Exception {
        region("spawn", 0, 0, 100, 100, false);
        try (AutoCloseable registration = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow())) {
            assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
            assertEquals(java.util.Set.of("EditGuardsTestRegions"), guards.ownRegionOwners());
        }
    }

    @Test
    void givenOrdinaryGuardThatAllows_whenItsOwnRegionDeniesBuild_thenTheFlagStillDenies() throws Exception {
        region("spawn", 0, 0, 100, 100, false);
        try (AutoCloseable registration = guards.register(owner, ask -> new EditVerdict.Allow())) {
            assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()), "a plain guard can only make the answer stricter");
            assertTrue(guards.ownRegionOwners().isEmpty());
        }
    }

    @Test
    void givenOwnRegionsGuard_whenAnotherPluginsRegionDeniesBuild_thenDeny() throws Exception {
        otherRegion("town", 0, 0, 100, 100, false);
        try (AutoCloseable registration = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow())) {
            EditVerdict.Deny deny = assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
            assertEquals("regions", deny.reasonKey());
        }
    }

    @Test
    void givenOwnRegionWithHigherPriorityAllowingOverAnotherPluginsDeny_whenOwnGuardRegistered_thenBothMustAllow() throws Exception {
        otherRegion("town", 0, 0, 100, 100, false);
        RegionService.get().register(owner, Region.of("edittest", "plot", new RegionShape.Box(worldId, 0, -64, 0, 100, 320, 100))
                .withPriority(5000).withFlags(Map.of("build", true)));
        assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask()), "before: the higher allowing region decides inside");
        try (AutoCloseable registration = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow())) {
            assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()), "with the own guard the other plugin's deny counts too");
        }
    }

    @Test
    void givenOwnRegionsGuardClosed_whenAsked_thenTheOwnFlagCountsAgain() throws Exception {
        region("spawn", 0, 0, 100, 100, false);
        AutoCloseable registration = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow());
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
        registration.close();
        assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
        assertTrue(guards.ownRegionOwners().isEmpty());
        registration.close();   // twice is harmless
        assertTrue(guards.ownRegionOwners().isEmpty());
    }

    @Test
    void givenOwnRegionsGuardOwnerDisabled_whenReleased_thenTheOwnFlagCountsAgain() {
        region("spawn", 0, 0, 100, 100, false);
        guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow());
        guards.release(owner);
        assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
        assertTrue(guards.ownRegionOwners().isEmpty());
    }

    @Test
    void givenTwoOwnRegionsGuardsOfOneOwner_whenOneIsClosed_thenTheOtherStillSkipsTheFlag() throws Exception {
        region("spawn", 0, 0, 100, 100, false);
        AutoCloseable first = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow());
        try (AutoCloseable second = guards.registerOwnRegions(owner, ask -> new EditVerdict.Allow())) {
            first.close();
            assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
        }
        assertInstanceOf(EditVerdict.Deny.class, guards.ask(ask()));
    }

    @Test
    void givenAskWithoutActor_whenOwnRegionsGuardAnswers_thenItsAnswerCounts() throws Exception {
        region("spawn", 0, 0, 100, 100, false);
        List<UUID> actors = new ArrayList<>();
        try (AutoCloseable registration = guards.registerOwnRegions(owner, ask -> {
            actors.add(ask.actor());
            return ask.actor() == null ? new EditVerdict.Deny("own-flag") : new EditVerdict.Allow();
        })) {
            EditVerdict.Deny deny = assertInstanceOf(EditVerdict.Deny.class, guards.ask(new EditAsk(editor, null, world, 2, 3, 0, 15)));
            assertEquals("own-flag", deny.reasonKey());
            assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()));
        }
        assertEquals(2, actors.size());
        assertEquals(null, actors.get(0));
    }

    @Test
    void givenOwnRegionsGuardPartialAndAnotherPluginsHalfDeny_whenCombined_thenEachCellNeedsBoth() throws Exception {
        region("spawn", 0, 0, 100, 100, false);          // own, skipped
        otherRegion("west", 0, 0, 39, 100, false);       // other plugin denies x < 40
        try (AutoCloseable registration = guards.registerOwnRegions(owner, ask -> new EditVerdict.Partial((x, y, z) -> z >= 56))) {
            EditVerdict.Partial partial = assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask()));
            assertTrue(partial.allowed().allowed(44, 0, 60));
            assertFalse(partial.allowed().allowed(44, 0, 50), "own guard denies z < 56");
            assertFalse(partial.allowed().allowed(36, 0, 60), "other plugin denies x < 40");
            assertTrue(partial.allowed().allowed(36, 0, 60 + 1000), "a cell outside the chunk asks the regions live: outside every region");
        }
    }

    @Test
    void givenPartialFromRegions_whenTheCellTestRunsAMillionTimes_thenItAllocatesNothing() {
        otherRegion("west", 0, 0, 39, 100, false);
        RegionService.get().register(other, Region.of("edittest", "ring", new RegionShape.Cylinder(worldId, 44, 60, 3, -64, 320))
                .withPriority(400).withFlags(Map.of("build", true)));
        CellTest test = assertInstanceOf(EditVerdict.Partial.class, guards.ask(ask())).allowed();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        int open = 0;
        for (int i = 0; i < 300_000; i++) if (test.allowed(32 + (i & 15), i & 15, 48 + ((i >> 4) & 15))) open++;
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long before = mx.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 1_000_000; i++) if (test.allowed(32 + (i & 15), i & 15, 48 + ((i >> 4) & 15))) open++;
            best = Math.min(best, mx.getThreadAllocatedBytes(thread) - before);
        }
        assertTrue(best < 64_000, "allocated " + best + " bytes for a million cells (" + open + ")");
    }

    @Test
    void givenOwnRegionsCapability_whenAsked_thenItIsThereSince0710() {
        assertEquals(java.util.Optional.of("0.71.0"), Vystorm.vystorm_core.CoreApi.since(Vystorm.vystorm_core.CoreApi.Capability.EDIT_ASK_OWN_REGIONS));
        assertEquals("edit-ask-own-regions", Vystorm.vystorm_core.CoreApi.Capability.EDIT_ASK_OWN_REGIONS);
        assertTrue(Vystorm.vystorm_core.CoreApi.has("edit-ask-own-regions"));
    }

    @Test
    void givenAReplacementEditGuardsWithoutTheNewMethod_whenRegisterOwnRegions_thenItRegistersAnOrdinaryGuard() {
        List<EditGuard> registered = new ArrayList<>();
        EditGuards replacement = (plugin, guard) -> { registered.add(guard); return () -> { }; };
        EditGuard guard = ask -> new EditVerdict.Allow();
        replacement.registerOwnRegions(owner, guard);
        assertEquals(List.of(guard), registered);
    }

    @Test
    void givenRegionAboveTheAskedHeight_whenAsked_thenAllow() {
        Region high = Region.of("edittest", "sky", new RegionShape.Box(worldId, 0, 200, 0, 100, 300, 100)).withFlags(Map.of("build", false));
        RegionService.get().register(owner, high);
        assertInstanceOf(EditVerdict.Allow.class, guards.ask(ask()), "the region starts above y 15");
    }
}
