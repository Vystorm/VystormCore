package Vystorm.vystorm_core._uiMethods.tutorial;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TutorialGuideTest {
    private static final String GUIDE = """
            title: Demo
            permission: demo.use
            pages:
              - title: Einstieg
                text: '1. Öffne das Menü.'
              - title: Verwaltung
                permission: demo.admin
                text: 'Nur zur Verwaltung.'
            """;

    @Test void givenTranslationsWhenPickedThenViewerLanguageElseDefaultElseEnglishForUnknown() throws Exception {
        var base = TutorialGuide.read(GUIDE);
        var en = TutorialGuide.read(GUIDE.replace("Einstieg", "Getting started"));
        var map = java.util.Map.of("en", en);
        assertSame(en, TutorialGuide.pick(map, base, java.util.List.of("en_us"), "en"));
        assertSame(base, TutorialGuide.pick(map, base, java.util.List.of("de_de"), "de"));
        assertSame(en, TutorialGuide.pick(map, base, java.util.List.of("fr_fr"), "en"));
        assertSame(base, TutorialGuide.pick(java.util.Map.of(), base, java.util.List.of("en_us"), "en"));
    }

    @Test void givenBrokenOrEmptyGuideWhenReadThenReject() {
        assertThrows(IllegalArgumentException.class, () -> TutorialGuide.read("title: Demo\npages: []"));
        assertThrows(IllegalArgumentException.class, () -> TutorialGuide.read("title: Demo\npages:\n- title: Leer"));
    }

    @Test void givenGuideWhenReadThenRetainStepsAndPagePermissions() throws Exception {
        var guide = TutorialGuide.read(GUIDE);
        assertEquals("Demo", guide.title());
        assertEquals("1. Öffne das Menü.", guide.pages().getFirst().text());
        assertEquals("demo.admin", guide.pages().getLast().permission());
        assertThrows(UnsupportedOperationException.class, () -> guide.pages().clear());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void givenAdminGuideWhenRightsRevokedThenHideAndRejectOldPage() {
        API api = mock(API.class);
        Plugin owner = mock(Plugin.class);
        GuiInteface gui = mock(GuiInteface.class);
        DialogInterface dialogs = mock(DialogInterface.class);
        Player player = mock(Player.class);
        when(api.gui()).thenReturn(gui); when(api.dialogs()).thenReturn(dialogs);
        when(owner.getName()).thenReturn("Demo"); when(owner.isEnabled()).thenReturn(true);
        when(owner.getResource("tutorial.yml")).thenReturn(new ByteArrayInputStream(GUIDE.getBytes(StandardCharsets.UTF_8)));
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("demo.use")).thenReturn(true);
        when(player.hasPermission("demo.admin")).thenReturn(true);
        TutorialGuide.register(api, owner);
        ArgumentCaptor<Consumer<Player>> opener = ArgumentCaptor.forClass((Class)Consumer.class);
        ArgumentCaptor<java.util.function.Function<Player, String>> title = ArgumentCaptor.forClass((Class) java.util.function.Function.class);
        verify(gui).registerMenu(eq(owner), eq("tutorial"), title.capture(), any(), any(), opener.capture(), eq("Tutorials"), any(java.util.function.Function.class));
        when(player.locale()).thenReturn(java.util.Locale.GERMANY);
        assertEquals("Demo · Anleitung", title.getValue().apply(player));
        try (var bodies = mockStatic(DialogBody.class)) {
            bodies.when(() -> DialogBody.plainMessage(any())).thenReturn(mock(DialogBody.class));
            opener.getValue().accept(player);
            ArgumentCaptor<DialogRequest> requests = ArgumentCaptor.forClass(DialogRequest.class);
            verify(dialogs).open(eq(owner), eq(player), requests.capture());
            var index = requests.getValue();
            index.buttons().get(1).action().accept(player, null);
            verify(dialogs, times(2)).open(eq(owner), eq(player), requests.capture());
            var adminPage = requests.getValue();
            when(player.hasPermission("demo.admin")).thenReturn(false);
            assertFalse(adminPage.valid().test(player));
            index.buttons().get(1).action().accept(player, null);
            verify(dialogs, times(2)).open(any(), any(), any(Vystorm.vystorm_core.compat.dialog.DialogRequest.class));
            opener.getValue().accept(player);
            verify(dialogs, times(3)).open(eq(owner), eq(player), requests.capture());
            assertEquals(2, requests.getValue().buttons().size()); // public topic and /vmenu
            when(owner.isEnabled()).thenReturn(false);
            assertFalse(requests.getValue().valid().test(player));
        }
    }
}
