package Vystorm.vystorm_core._uiMethods.tutorial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreOnboardingTest {
    @Test void milestonesBecomeCompleteOnlyAfterExplicitActions() {
        CoreOnboarding.Progress state = new CoreOnboarding.Progress(false, false, false, false);
        assertFalse(state.complete());
        state = state.languageChosenNow();
        state = state.menuOpened();
        assertFalse(state.complete(), "opening a menu does not confirm a completed plugin action");
        state = state.menuOpened();
        assertFalse(state.complete(), "reopening menus must not complete the feature task");
        state = state.featureConfirmed();
        assertTrue(state.complete());
    }

    @Test void skipDoesNotForgeMilestonesAndResetClearsSkipAndProgress() {
        CoreOnboarding.Progress state = new CoreOnboarding.Progress(true, false, false, false).skipNow();
        assertTrue(state.complete());
        assertFalse(state.languageChosen() && state.firstMenuOpened() && state.pluginFunctionOpened());
        assertEquals(new CoreOnboarding.Progress(false, false, false, false), state.resetNow());
    }
}
