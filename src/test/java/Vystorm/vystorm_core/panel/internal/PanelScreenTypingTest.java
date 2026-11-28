package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.compat.internal.ScreenDialogs;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Typing into a text field of a panel shown as a Vystorm Screen (0.71.0 fix). Since 0.61.0 every dialog with an input
 * opened as a screen form; that form replaced the panel's screen, so the panel closed under the player (Core's player
 * picker counted the close as "another menu" and ended: search gave no results and the picker was gone) and typing
 * took two forms. The typing box must come from the native/chat handler behind the screen wrapper.
 */
class PanelScreenTypingTest {
    @Test
    void givenTheScreenWrapper_whenAPanelScreenAsksForTyping_thenTheWrappedNativeHandlerOpensIt() {
        Vystorm.vystorm_core._uiMethods.dialog.DialogInterface nativeDialogs = mock(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class);
        ScreenDialogs screens = new ScreenDialogs(nativeDialogs);
        assertSame(nativeDialogs, PanelScreens.typingDialogs(screens), "never the screen form: it would close the panel's screen");
    }

    @Test
    void givenAPlainHandler_whenAPanelScreenAsksForTyping_thenThatHandler() {
        Vystorm.vystorm_core.compat.dialog.DialogInterface plain = mock(Vystorm.vystorm_core.compat.dialog.DialogInterface.class);
        assertSame(plain, PanelScreens.typingDialogs(plain));
    }

    @Test
    void givenDrawnActs_whenTheTypingActOfAnInputIsLookedUp_thenOnlyThatInputsEdit() {
        Map<String, PanelScreenLayout.Act> acts = new LinkedHashMap<>();
        acts.put("w1", new PanelScreenLayout.Act.Close());
        PanelScreenLayout.Act.Edit search = new PanelScreenLayout.Act.Edit("search", null, "", 32, null, true);
        acts.put("w2", search);
        acts.put("w3", new PanelScreenLayout.Act.Edit("name", null, "", 32, null, false));
        assertSame(search, PanelScreens.editFor(acts, "search"));
        assertNull(PanelScreens.editFor(acts, "missing"));
        assertNull(PanelScreens.editFor(null, "search"));
    }

    @Test
    void givenATypingRequest_whenTaken_thenOnceAndOnlyForThatPlayer() {
        Player a = mock(Player.class), b = mock(Player.class);
        when(a.getUniqueId()).thenReturn(new UUID(0, 1));
        when(b.getUniqueId()).thenReturn(new UUID(0, 2));
        PanelOpener.typeNext(a, "search");
        assertNull(PanelOpener.takeTypeNext(b));
        assertEquals("search", PanelOpener.takeTypeNext(a));
        assertNull(PanelOpener.takeTypeNext(a), "one use");
    }
}
