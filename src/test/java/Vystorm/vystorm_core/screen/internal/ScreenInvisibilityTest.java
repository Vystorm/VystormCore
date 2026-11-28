package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenInput;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 0.71.1: a PINNED screen must never leave a player invisible. Uses only the screen calls that 0.71.0 had too, so the
 * same class shows which paths really failed there (see the CHANGELOG: spectator and invisibility potion).
 */
class ScreenInvisibilityTest {
    private final World world = PaperPlayerModel.world();
    private final Plugin core = PaperPlayerModel.core();
    private final PaperPlayerModel.Entities entities = new PaperPlayerModel.Entities();
    private ScreenRuntime runtime;

    @BeforeEach void setUp() { runtime = new ScreenRuntime(core, entities, () -> 100_000L, p -> true, p -> false); }

    private void openPinned(PaperPlayerModel model, String id) { runtime.open(core, model.player, PaperPlayerModel.spec(id)); }

    @Test void givenVisiblePlayer_whenPinnedOpens_thenInvisibleMeanwhileAndVisibleAfter() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model, "test:a");
        assertTrue(model.invisible(), "no empty arm over the screen");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertFalse(model.invisible());
        assertFalse(model.persistent);
    }

    @Test void givenInvisibilityPotion_whenPinnedOpensAndCloses_thenVisibleOnceThePotionEnds() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.drinkInvisibility();
        openPinned(model, "test:a");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertTrue(model.invisible(), "the potion still runs");
        model.effectEnds();
        assertFalse(model.invisible(), "0.71.0 wrote setInvisible(true) back: invisible for good");
        assertFalse(model.save().bukkitInvisible());
    }

    @Test void givenSpectator_whenAScreenIsAskedForAndClosed_thenVisibleAfterLeavingSpectatorMode() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.gameMode(true);
        assertTrue(model.invisible(), "the game shows spectators invisible");
        openPinned(model, "vystorm_core:menu");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        model.gameMode(false);
        assertFalse(model.invisible(), "0.71.0 opened the screen for a spectator and wrote setInvisible(true) back");
        assertFalse(model.save().bukkitInvisible());
    }

    @Test void givenOpenScreen_whenAnotherReplacesItAndThatCloses_thenVisible() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model, "test:a");
        openPinned(model, "test:b");
        assertTrue(model.invisible());
        openPinned(model, "test:c");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertFalse(model.invisible());
        assertFalse(model.persistent);
    }

    @Test void givenOpenScreen_whenTheScreenClosesItselfAndANewOneOpensInTheSameTick_thenVisibleAfterTheLast() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model, "test:a");
        runtime.close(model.player, ScreenInput.Reason.PLUGIN);
        openPinned(model, "test:b");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertFalse(model.invisible());
    }

    @Test void givenOpenScreen_whenThePlayerQuits_thenTheSavedFileIsVisible() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        openPinned(model, "test:a");
        runtime.close(model.player, ScreenInput.Reason.QUIT);   // PlayerQuitEvent comes before PlayerList saves the player
        PaperPlayerModel.Saved file = model.save();
        assertFalse(file.bukkitInvisible());
        assertTrue(file.data().isEmpty(), "no marker left");
        assertFalse(PaperPlayerModel.load(world, file).invisible());
    }

    @Test void givenOpenScreen_whenThePlayerDiesOrChangesWorld_thenVisible() {
        PaperPlayerModel dies = new PaperPlayerModel(world), travels = new PaperPlayerModel(world);
        openPinned(dies, "test:a");
        openPinned(travels, "test:a");
        runtime.close(dies.player, ScreenInput.Reason.DEATH);
        runtime.close(travels.player, ScreenInput.Reason.WORLD);
        assertFalse(dies.invisible());
        assertFalse(travels.invisible());
    }

    @Test void givenPlayerMadeInvisibleByAnotherPlugin_whenPinnedOpensAndCloses_thenStillInvisible() {
        PaperPlayerModel model = new PaperPlayerModel(world);
        model.player.setInvisible(true);   // a vanish plugin
        openPinned(model, "test:a");
        runtime.close(model.player, ScreenInput.Reason.PLAYER);
        assertTrue(model.invisible());
        assertTrue(model.persistent);
    }
}
