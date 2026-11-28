package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.71.1: per-player bookkeeping of what PINNED screens change, its marker, the join check and the repair command. */
class ScreenHandHidingTest {
    private final World world = PaperPlayerModel.world();
    private final Plugin core = PaperPlayerModel.core();
    private final PaperPlayerModel.Entities entities = new PaperPlayerModel.Entities();
    private final List<String> told = new ArrayList<>();
    private final AtomicBoolean repair = new AtomicBoolean();
    private ScreenRuntime runtime;

    @BeforeEach void setUp() {
        runtime = new ScreenRuntime(core, entities, () -> 100_000L, p -> true, p -> false);
        runtime.invisibleRules(p -> p.getGameMode() == org.bukkit.GameMode.SPECTATOR, repair::get, p -> true,
                (p, key) -> told.add(p.getName() + ":" + key));
    }

    @AfterEach void tearDown() { ScreenRuntime.install(null); }

    private void openPinned(PaperPlayerModel model) { runtime.open(core, model.player, PaperPlayerModel.spec("test:a")); }

    // ---- per player, not per screen ------------------------------------------------------------------------------

    @Test void givenTwoHolders_whenTheyLetGoInEitherOrder_thenTheStateBeforeComesBackOnceAtTheLast() {
        for (boolean firstLetsGoFirst : new boolean[] { true, false }) {
            HandHiding hiding = new HandHiding(line -> { });
            PaperPlayerModel model = new PaperPlayerModel(world);
            model.weather = WeatherType.DOWNFALL;
            Object first = new Object(), second = new Object();
            hiding.hide(model.player, first);
            hiding.hide(model.player, second);   // reads nothing: Core's own invisibility is not "before"
            hiding.show(model.player, firstLetsGoFirst ? first : second);
            assertTrue(model.invisible(), "one screen still needs it");
            assertEquals(WeatherType.CLEAR, model.weather);
            hiding.show(model.player, firstLetsGoFirst ? second : first);
            assertFalse(model.invisible());
            assertFalse(model.persistent);
            assertEquals(WeatherType.DOWNFALL, model.weather, "the player's own weather, not the server's");
            assertTrue(model.data.isEmpty());
        }
    }

    @Test void givenHiddenPlayer_whenSaved_thenTheFileHasTheMarkerWithTheFlag() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model);
        PaperPlayerModel.Saved file = model.save();
        assertTrue(file.bukkitInvisible());
        assertEquals("1;-1", file.data().get("vystorm_core:screen-hidden"));
    }

    // ---- crash: the next join repairs ----------------------------------------------------------------------------

    @Test void givenAutosaveWhileHiddenAndAKill_whenThePlayerJoinsAgain_thenVisibleAndSlotBack() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.sword();
        openPinned(model);
        assertEquals(1, model.heldSlot);
        PaperPlayerModel.Saved file = model.save();   // autosave, then kill -9: no quit, no disable
        assertEquals("1;0", file.data().get("vystorm_core:screen-hidden"));

        ScreenRuntime restarted = new ScreenRuntime(core, entities, () -> 100_000L, p -> true, p -> false);
        PaperPlayerModel joined = PaperPlayerModel.load(world, file);
        joined.sword();
        joined.heldSlot = file.heldSlot();
        assertTrue(joined.invisible(), "Bukkit.invisible came back from the file");
        assertEquals(HandHiding.JoinCheck.MARKER_RESTORED, restarted.onJoin(joined.player));
        assertFalse(joined.invisible());
        assertFalse(joined.persistent);
        assertEquals(0, joined.heldSlot);
        assertTrue(joined.data.isEmpty());
        assertFalse(joined.save().bukkitInvisible());
    }

    @Test void givenMarkerButAlreadyVisible_whenJoining_thenOnlyTheMarkerGoes() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.data.put("vystorm_core:screen-hidden", "1;-1");
        assertEquals(HandHiding.JoinCheck.MARKER_RESTORED, runtime.onJoin(model.player));
        assertFalse(model.invisible());
        assertTrue(model.data.isEmpty());
    }

    // ---- stuck players from 0.71.0 and older (no marker) --------------------------------------------------------

    @Test void givenInvisibleWithoutEffectOrMarker_whenJoining_thenReportedButNotTouched() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);
        assertEquals(HandHiding.JoinCheck.REPORTED, runtime.onJoin(model.player));
        assertTrue(model.invisible(), "without the switch Core never un-hides on its own");
        assertEquals(List.of("Tester:screen.visible.stuck-admin"), told);
    }

    @Test void givenRepairSwitchOn_whenAStuckPlayerJoins_thenVisible() {
        repair.set(true);
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);
        assertEquals(HandHiding.JoinCheck.REPAIRED, runtime.onJoin(model.player));
        assertFalse(model.invisible());
        assertFalse(model.persistent);
        assertEquals(List.of("Tester:screen.visible.player"), told);
    }

    @Test void givenSpectatorOrPotion_whenJoining_thenNeitherReportedNorTouched() {
        repair.set(true);
        PaperPlayerModel spectator = new PaperPlayerModel(world);
        spectator.gameMode(true);
        assertEquals(HandHiding.JoinCheck.NONE, runtime.onJoin(spectator.player));
        assertTrue(spectator.invisible());
        runtime.invisibleRules(p -> p == spectator.player, null, null, null);
        PaperPlayerModel visible = new PaperPlayerModel(world);
        assertEquals(HandHiding.JoinCheck.NONE, runtime.onJoin(visible.player));
        assertTrue(told.isEmpty());
    }

    @Test void givenStuckPlayer_whenAdminRunsVisible_thenVisibleAndTold() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);
        model.weather = WeatherType.DOWNFALL;
        assertTrue(runtime.makeVisible(model.player));
        assertFalse(model.invisible());
        assertFalse(model.persistent);
        assertEquals(WeatherType.DOWNFALL, model.weather, "another plugin's player weather stays");
        assertEquals(List.of("Tester:screen.visible.player"), told);
    }

    /** Like the live refresh (effect off and on): the game works the flag out again. */
    private void refreshLikeTheServer(PaperPlayerModel... models) {
        runtime.invisibleEffectRefresh(p -> { for (PaperPlayerModel m : models) if (m.player == p) m.gameUpdate(); });
    }

    @Test void givenPotionDrunkWhileTheScreenIsOpen_whenItCloses_thenThePotionStillCountsAndEndsNormally() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        refreshLikeTheServer(model);
        openPinned(model);
        model.drinkInvisibility();
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertTrue(model.invisible(), "the potion's invisibility");
        assertFalse(model.persistent, "but not Core's persistent flag");
        model.effectEnds();
        assertFalse(model.invisible());
    }

    @Test void givenStuckSpectatorAtJoin_whenLeavingSpectatorMode_thenReportedAMomentLater() {
        List<Runnable> queued = new ArrayList<>();
        runtime = new ScreenRuntime(core, entities, () -> 100_000L, p -> true, p -> false, queued::add);
        runtime.invisibleRules(p -> p.getGameMode() == org.bukkit.GameMode.SPECTATOR, repair::get, p -> true, (p, key) -> told.add(p.getName() + ":" + key));
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);   // left by 0.71.0
        model.gameMode(true);
        assertEquals(HandHiding.JoinCheck.NONE, runtime.onJoin(model.player), "a spectator is invisible anyway");
        runtime.onGameMode(model.player, org.bukkit.GameMode.SURVIVAL);   // the event comes before the change
        model.gameMode(false);
        assertTrue(model.invisible(), "the game cannot clear the persistent flag");
        queued.forEach(Runnable::run);
        assertEquals(List.of("Tester:screen.visible.stuck-admin"), told);
    }

    @Test void givenSlotOnlyMarker_whenJoining_thenSlotBackAndTheStuckCheckStillRuns() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.sword();
        model.heldSlot = 1;
        model.player.setInvisible(true);
        model.data.put("vystorm_core:screen-hidden", "0;0");
        assertEquals(HandHiding.JoinCheck.REPORTED, runtime.onJoin(model.player));
        assertEquals(0, model.heldSlot);
        assertTrue(model.data.isEmpty());
        assertTrue(model.invisible(), "Core had not hidden them: not cleared");
    }

    @Test void givenStuckPlayer_whenAPinnedScreenOpensAndCloses_thenCoreDoesNotMakeItWorse() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);
        openPinned(model);
        assertTrue(model.data.isEmpty(), "Core did not hide them, so no marker");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertTrue(model.invisible(), "untouched: only the repair command or the switch clears it");
    }

    // ---- every way out -------------------------------------------------------------------------------------------

    @Test void givenOpenScreens_whenCoreIsDisabled_thenEveryoneVisibleBeforeTheSave() {
        ScreenRuntime.install(runtime);
        PaperPlayerModel a = new PaperPlayerModel(world), b = new PaperPlayerModel(world);
        openPinned(a);
        openPinned(b);
        ScreenRuntime.uninstall();
        assertFalse(a.save().bukkitInvisible());
        assertFalse(b.save().bukkitInvisible());
        assertTrue(a.data.isEmpty() && b.data.isEmpty());
    }

    @Test void givenOpenScreen_whenSwitchingToSpectator_thenClosedFirstAndTheGameDecidesAfterwards() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model);
        runtime.onGameMode(model.player, org.bukkit.GameMode.SPECTATOR);
        assertFalse(runtime.hasSession(model.player));
        assertFalse(model.persistent);
        model.gameMode(true);
        assertTrue(model.invisible(), "spectator: the game's own flag");
        model.gameMode(false);
        assertFalse(model.invisible());
    }

    @Test void givenRemovalAndTeleportFailWhileClosing_whenTheScreenCloses_thenStillVisibleAndClosed() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model);
        entities.failRemove = true;
        org.mockito.Mockito.when(model.player.teleport(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class))).thenThrow(new IllegalStateException("teleport refused"));
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertFalse(model.invisible());
        assertTrue(model.data.isEmpty());
        assertFalse(runtime.hasSession(model.player));
    }

    @Test void givenWeatherFailsWhileHiding_whenTheScreenCloses_thenVisibleAnyway() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.weatherFailure = new UnsupportedOperationException("no player weather");
        openPinned(model);
        assertTrue(model.invisible());
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertFalse(model.invisible());
    }

    @Test void givenOwnPlayerWeather_whenAScreenOpensAndCloses_thenItComesBack() {
        PaperPlayerModel own = new PaperPlayerModel(world), none = new PaperPlayerModel(world);
        own.weather = WeatherType.DOWNFALL;
        openPinned(own);
        openPinned(none);
        assertEquals(WeatherType.CLEAR, own.weather);
        runtime.close(own.player, ScreenInput.Reason.PLAYER);
        runtime.close(none.player, ScreenInput.Reason.PLAYER);
        assertEquals(WeatherType.DOWNFALL, own.weather);
        assertNull(none.weather, "reset: the server's weather again");
    }

    @Test void givenSwordHeld_whenThePlayerQuitsWithTheScreenOpen_thenTheSlotIsBackBeforeTheSave() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.sword();
        openPinned(model);
        assertEquals(1, model.heldSlot);
        runtime.close(model.player, ScreenInput.Reason.QUIT);
        assertEquals(0, model.save().heldSlot());
    }

    @Test void givenBrokenMarker_whenRead_thenNoSlot() {
        assertEquals(-1, HandHiding.slotOf("1"));
        assertEquals(-1, HandHiding.slotOf("1;x"));
        assertEquals(-1, HandHiding.slotOf("1;12"));
        assertEquals(4, HandHiding.slotOf("0;4"));
    }
}
