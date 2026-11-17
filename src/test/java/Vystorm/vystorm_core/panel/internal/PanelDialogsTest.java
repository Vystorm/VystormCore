package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core._uiMethods.dialog.DialogInterface;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.compat.dialog.DialogResponseView;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Automatischer Paper-Dialog (Final Review Important 8 und Minors): Bestätigung, lazy Reiter, Texte, Ratenlimit. */
class PanelDialogsTest {
    final List<DialogRequest> shown = new ArrayList<>();
    final AtomicInteger clicks = new AtomicInteger(), tabs = new AtomicInteger();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    MockedStatic<DialogBody> bodies;
    MockedStatic<DialogInput> inputs;
    Player player;
    PanelHub hub;

    @BeforeEach
    void setUp() {
        PanelRegistry.clear();
        bodies = mockStatic(DialogBody.class, RETURNS_DEEP_STUBS);
        inputs = mockStatic(DialogInput.class, RETURNS_DEEP_STUBS);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Example");
        DialogInterface dialogs = mock(DialogInterface.class);
        doAnswer(i -> shown.add(i.getArgument(2))).when(dialogs).open(any(), any(), any(DialogRequest.class));
        PanelChannel channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> Protocol.CAP_PANELS, (p, d) -> { },
                Logger.getAnonymousLogger(), nanos::get);
        hub = new PanelHub(channel, nanos::get, Logger.getAnonymousLogger(), e -> { }, Runnable::run, Runnable::run);
        hub.dialogs(mock(Plugin.class), () -> dialogs);
        PanelRegistry.register(owner, "example:d", PanelSpec.builder("D").dialogFallback(true).render(ctx -> Panel.page("D").children(
                Ui.button("rm").label("Löschen").danger().confirm("Wirklich?", "Weg ist weg").onClick(a -> { clicks.incrementAndGet(); return Result.ok(); }),
                Ui.button("ok").label("OK").onClick(a -> { clicks.incrementAndGet(); return Result.ok(); }),
                Ui.tabs("x").tab("a", "A", Ui.text("in a")).lazyTab("b", "B").onTab((a, t) -> { tabs.incrementAndGet(); return Result.ok(); })))
                .build());
    }

    @AfterEach
    void tearDown() {
        bodies.close();
        inputs.close();
        PanelRegistry.clear();
    }

    static String label(DialogButton b) { return PlainTextComponentSerializer.plainText().serialize(b.label()); }

    DialogButton button(DialogRequest r, String label) {
        return r.buttons().stream().filter(b -> label(b).equals(label)).findFirst().orElseThrow(() -> new AssertionError(label + " in " + r.buttons().stream().map(PanelDialogsTest::label).toList()));
    }

    void press(DialogRequest r, String label) { button(r, label).action().accept(player, mock(DialogResponseView.class)); }

    @Test
    void givenDangerButtonWithConfirm_whenPressedInDialog_thenConfirmationFirst() {
        assertTrue(hub.openDialog(player, PanelRegistry.find("example:d").orElseThrow(), ""));
        press(shown.getLast(), "Löschen");
        assertEquals(0, clicks.get(), "not run before the player confirms");
        DialogRequest confirm = shown.getLast();
        assertEquals("Wirklich?", PlainTextComponentSerializer.plainText().serialize(confirm.title()));
        press(confirm, Localization.get().core().plain(player, "panel.dialog.yes"));
        assertEquals(1, clicks.get());
        press(shown.getLast(), "OK");
        assertEquals(2, clicks.get(), "buttons without confirm run directly");
    }

    @Test
    void givenLazyTab_whenChosenInDialog_thenOnTabRuns() {
        hub.openDialog(player, PanelRegistry.find("example:d").orElseThrow(), "");
        press(shown.getLast(), "B");
        assertEquals(1, tabs.get());
    }

    @Test
    void givenDialog_whenShown_thenCloseLabelIsLocalized() {
        hub.openDialog(player, PanelRegistry.find("example:d").orElseThrow(), "");
        assertEquals(Localization.get().core().plain(player, "panel.dialog.close"), shown.getLast().cancelLabel());
        assertNotEquals("panel.dialog.close", shown.getLast().cancelLabel());
    }

    @Test
    void givenDialogFlood_whenRateLimited_thenSlowDownInsteadOfNoPermission() {
        hub.openDialog(player, PanelRegistry.find("example:d").orElseThrow(), "");
        String slow = Localization.get().core().plain(player, "panel.dialog.slow-down");
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) messages.add(hub.dialogs().action(player, "ok", Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.KIND_CLICK, "").message());
        assertTrue(messages.contains(slow), messages.toString());
        assertFalse(messages.contains(Localization.get().core().plain(player, "panel.no-permission")));
    }
}
