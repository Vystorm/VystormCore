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

    @Test void givenOneClickArrivingTwice_thenNothing() {
        assertFalse(shortcut.leftClick(player));
        now += 30;
        assertFalse(shortcut.leftClick(player), "two signals of one click are one click");
        now += 150;
        assertTrue(shortcut.leftClick(player), "a real second click still opens");
    }

    @Test void givenBlockClicks_thenTheyNeverCount() {
        assertTrue(MenuShortcut.counts(org.bukkit.event.block.Action.LEFT_CLICK_AIR));
        assertFalse(MenuShortcut.counts(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK), "mining or breaking while sneaking");
        assertFalse(MenuShortcut.counts(org.bukkit.event.block.Action.RIGHT_CLICK_AIR));
        assertFalse(MenuShortcut.counts(org.bukkit.event.block.Action.PHYSICAL));
    }
}
