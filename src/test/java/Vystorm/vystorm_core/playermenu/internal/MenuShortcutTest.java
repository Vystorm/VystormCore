package Vystorm.vystorm_core.playermenu.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MenuShortcutTest {
    private long now = 10_000;
    private final MenuShortcut shortcut = new MenuShortcut(() -> now, () -> true, p -> false);
    private final UUID player = UUID.randomUUID();

    @Test void givenTwoFastLeftClicks_thenOpens() {
        assertFalse(shortcut.leftClick(player));
        now += 200;
        assertTrue(shortcut.leftClick(player));
    }

    @Test void givenTwoSlowLeftClicks_thenNothing() {
        shortcut.leftClick(player);
        now += 300;
        assertFalse(shortcut.leftClick(player));
    }

    @Test void givenSlowThenFast_thenSecondPairOpens() {
        shortcut.leftClick(player);
        now += 300;
        assertFalse(shortcut.leftClick(player));
        now += 100;
        assertTrue(shortcut.leftClick(player), "the slow click starts the next pair");
    }

    @Test void givenOpened_thenThirdClickStartsFresh() {
        shortcut.leftClick(player);
        now += 100;
        assertTrue(shortcut.leftClick(player));
        now += 100;
        assertFalse(shortcut.leftClick(player));
    }
}
