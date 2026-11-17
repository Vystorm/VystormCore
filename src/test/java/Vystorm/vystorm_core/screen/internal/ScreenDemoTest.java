package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenWidget;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ScreenDemoTest {
    @Test void givenDemo_thenLayoutHasThreeButtonsAndOneInput() {
        Lang lang = mock(Lang.class);
        when(lang.component(any(org.bukkit.command.CommandSender.class), anyString(), any(Object[].class))).thenReturn(Component.text("x"));
        ScreenLayout layout = ScreenDemo.layout(lang, mock(Player.class));
        assertEquals(3, layout.widgets().stream().filter(w -> w.kind() == ScreenWidget.Kind.BUTTON).count());
        assertEquals(1, layout.widgets().stream().filter(w -> w.kind() == ScreenWidget.Kind.INPUT).count());
        for (ScreenWidget w : layout.widgets()) {
            assertTrue(w.u() >= 0 && w.u() + w.width() <= layout.width() + 1e-9, w.id() + " inside horizontally");
            assertTrue(w.v() >= 0 && w.v() + w.height() <= layout.height() + 1e-9, w.id() + " inside vertically");
        }
    }
}
