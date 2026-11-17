package Vystorm.vystorm_core._uiMethods.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import org.junit.jupiter.api.Test;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import Vystorm.vystorm_core.main;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Response validation is deliberately pure: clients control this payload. */
class DefaultDialogHandlerTest {
    @Test
    void givenForgedOrMalformedFormValuesWhenValidatedThenRejectBeforePluginCallback() {
        TextDialogInput text = text("name", 3);
        NumberRangeDialogInput number = number("amount", 1, 5);
        SingleOptionDialogInput choice = choice("kind", "oak");
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("toolong");
        when(response.getFloat("amount")).thenReturn(Float.NaN);
        when(response.getText("kind")).thenReturn("forged");
        assertFalse(DefaultDialogHandler.validResponse(List.of(text), response));
        assertFalse(DefaultDialogHandler.validResponse(List.of(number), response));
        assertFalse(DefaultDialogHandler.validResponse(List.of(choice), response));
    }

    @Test
    void givenCompleteValidFormWhenValidatedThenAcceptAndAllowNoInputCancelPayload() {
        TextDialogInput text = text("name", 3);
        NumberRangeDialogInput number = number("amount", 1, 5);
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("oak");
        when(response.getFloat("amount")).thenReturn(3F);
        assertTrue(DefaultDialogHandler.validResponse(List.of(text, number), response));
        assertTrue(DefaultDialogHandler.validResponse(List.of(), null));
    }

    @Test
    void givenButtonClick_whenClientHandlesAfterAction_thenDialogClosesInsteadOfWaitingScreen() {
        // Review I5: Vanilla 26.2 bleibt bei WAIT_FOR_RESPONSE auf „Warte auf Server“, closeDialog() schließt das nicht.
        assertEquals(io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction.CLOSE, DefaultDialogHandler.AFTER_ACTION);
    }

    @Test
    void givenReplacementAndOldDeferredCloseWhenTasksRunThenOldShowIsCancelledAndNewDialogSurvives() throws Exception {
        try (Harness harness = new Harness()) {
            DefaultDialogHandler dialogs = new DefaultDialogHandler();
            dialogs.open(harness.owner, harness.player, harness.request(() -> {}));
            BukkitTask firstShow = harness.tasks.getFirst().task;
            dialogs.close(harness.player); // removes the old session but defers its native close
            Runnable oldClose = harness.tasks.get(1).runnable;
            dialogs.open(harness.owner, harness.player, harness.request(() -> {}));
            verify(firstShow).cancel();
            oldClose.run();
            verify(harness.player, never()).closeDialog();
        }
    }

    @Test
    void givenReplayWrongViewerAndDisabledOwnerWhenHandlingThenNoCallbackRuns() throws Exception {
        try (Harness harness = new Harness()) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            DefaultDialogHandler dialogs = new DefaultDialogHandler();
            dialogs.open(harness.owner, harness.player, harness.request(calls::incrementAndGet));
            String token = harness.token(dialogs, harness.player);
            net.kyori.adventure.key.Key action = net.kyori.adventure.key.Key.key("vystorm_core", "dialog/" + token + "/0");
            Player other = mock(Player.class); when(other.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            dialogs.handle(other, action, mock(DialogResponseView.class));
            assertEquals(0, calls.get());
            when(harness.owner.isEnabled()).thenReturn(false);
            dialogs.handle(harness.player, action, mock(DialogResponseView.class));
            for (Harness.Task task : List.copyOf(harness.tasks)) task.runnable.run();
            assertEquals(0, calls.get());
        }
    }

    @Test
    void nullPayloadButtonRunsOnceAndReplayIsIgnored() throws Exception {
        try (Harness h = new Harness()) {
            DefaultDialogHandler dialogs = new DefaultDialogHandler();
            Runnable action = mock(Runnable.class);
            dialogs.open(h.owner, h.player, h.request(action));
            var key = h.key(dialogs, 0);
            dialogs.handle(h.player, key, null);
            Runnable dispatch = h.tasks.getLast().runnable;
            dialogs.handle(h.player, key, null);
            dispatch.run(); dispatch.run();
            dialogs.handle(h.player, key, null);
            verify(action, times(1)).run();
        }
    }

    @Test
    void acceptedResponseCanStillBeRevokedBeforeDispatch() throws Exception {
        for (String operation : List.of("close", "clear", "remove", "replace", "permission")) {
            try (Harness h = new Harness()) {
                DefaultDialogHandler dialogs = new DefaultDialogHandler();
                Runnable action = mock(Runnable.class);
                var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
                DialogRequest request = h.request(action);
                dialogs.open(h.owner, h.player, new DialogRequest(request.title(), request.body(), request.inputs(),
                        request.buttons(), p -> allowed.get(), request.cancel()));
                dialogs.handle(h.player, h.key(dialogs, 0), null);
                Runnable dispatch = h.tasks.getLast().runnable;
                switch (operation) {
                    case "close" -> dialogs.close(h.player);
                    case "clear" -> dialogs.clear();
                    case "remove" -> dialogs.removeAll(h.owner);
                    case "replace" -> dialogs.open(h.owner, h.player, h.request(() -> {}));
                    case "permission" -> allowed.set(false);
                }
                dispatch.run();
                verifyNoInteractions(action);
            }
        }
    }

    @Test
    void onlyExplicitExitRunsCancelAndOffThreadOpenIsRejected() throws Exception {
        try (Harness h = new Harness()) {
            DefaultDialogHandler dialogs = new DefaultDialogHandler();
            Runnable cancel = mock(Runnable.class);
            var original = h.request(() -> fail("unexpected submit"));
            var request = new DialogRequest(original.title(), original.body(), original.inputs(), original.buttons(), p -> true, cancel);
            dialogs.open(h.owner, h.player, request);
            dialogs.handle(h.player, h.key(dialogs, -1), null);
            h.tasks.getLast().runnable.run();
            verify(cancel).run();
            dialogs.open(h.owner, h.player, request);
            dialogs.clear();
            verifyNoMoreInteractions(cancel);
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> dialogs.open(h.owner, h.player, request));
        }
    }

    @Test
    void givenDialogWithoutChoice_whenItTimesOut_thenCancelRunsOnceAndNotAfterExplicitExit() throws Exception {
        try (Harness h = new Harness()) {
            DefaultDialogHandler dialogs = new DefaultDialogHandler();
            Runnable cancel = mock(Runnable.class);
            var original = h.request(() -> fail("unexpected submit"));
            var request = new DialogRequest(original.title(), original.body(), original.inputs(), original.buttons(), p -> true, cancel);
            dialogs.open(h.owner, h.player, request);
            Runnable timeout = h.later.getLast();
            timeout.run();
            timeout.run();
            verify(cancel, times(1)).run();

            Runnable second = mock(Runnable.class);
            var other = new DialogRequest(original.title(), original.body(), original.inputs(), original.buttons(), p -> true, second);
            dialogs.open(h.owner, h.player, other);
            Runnable secondTimeout = h.later.getLast();
            dialogs.handle(h.player, h.key(dialogs, -1), null);
            h.tasks.getLast().runnable.run();
            secondTimeout.run();
            verify(second, times(1)).run();
        }
    }

    private static TextDialogInput text(String key, int max) {
        TextDialogInput input = mock(TextDialogInput.class); when(input.key()).thenReturn(key); when(input.maxLength()).thenReturn(max); return input;
    }
    private static NumberRangeDialogInput number(String key, float start, float end) {
        NumberRangeDialogInput input = mock(NumberRangeDialogInput.class); when(input.key()).thenReturn(key); when(input.start()).thenReturn(start); when(input.end()).thenReturn(end); return input;
    }
    private static SingleOptionDialogInput choice(String key, String allowed) {
        SingleOptionDialogInput.OptionEntry entry = mock(SingleOptionDialogInput.OptionEntry.class); when(entry.id()).thenReturn(allowed);
        SingleOptionDialogInput input = mock(SingleOptionDialogInput.class); when(input.key()).thenReturn(key); when(input.entries()).thenReturn(List.of(entry)); return input;
    }

    private static final class Harness implements AutoCloseable {
        record Task(Runnable runnable, BukkitTask task) { }
        final Plugin core = mock(Plugin.class), owner = mock(Plugin.class);
        final Player player = mock(Player.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final java.util.List<Task> tasks = new java.util.ArrayList<>();
        final java.util.List<Runnable> later = new java.util.ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<main> mainMock = mockStatic(main.class);
        Harness() {
            when(core.isEnabled()).thenReturn(true); when(owner.isEnabled()).thenReturn(true); when(player.isOnline()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            mainMock.when(main::getPlugin).thenReturn(core);
            when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                BukkitTask task = mock(BukkitTask.class); tasks.add(new Task(invocation.getArgument(1), task)); return task;
            });
            when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                later.add(invocation.getArgument(1)); return mock(BukkitTask.class);
            });
        }
        DialogRequest request(Runnable action) {
            return new DialogRequest(net.kyori.adventure.text.Component.text("Test"), List.of(), List.of(),
                    List.of(new DialogButton(net.kyori.adventure.text.Component.text("Go"), (p, response) -> action.run())), p -> true, () -> fail("cancel must be explicit"));
        }
        net.kyori.adventure.key.Key key(DefaultDialogHandler dialogs, int index) throws Exception {
            return net.kyori.adventure.key.Key.key("vystorm_core", "dialog/" + token(dialogs, player) + "/" + index);
        }
        String token(DefaultDialogHandler dialogs, Player viewer) throws Exception {
            var sessions = DefaultDialogHandler.class.getDeclaredField("sessions"); sessions.setAccessible(true);
            Object session = ((java.util.Map<?, ?>) sessions.get(dialogs)).get(viewer.getUniqueId());
            var token = session.getClass().getDeclaredField("token"); token.setAccessible(true); return (String) token.get(session);
        }
        public void close() { mainMock.close(); bukkit.close(); }
    }

    @Test void givenCoreCancelLabels_whenRendered_thenFollowViewerLanguage() {
        Player german = mock(Player.class), english = mock(Player.class);
        when(german.locale()).thenReturn(java.util.Locale.GERMANY);
        when(english.locale()).thenReturn(java.util.Locale.US);
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        assertEquals("Abbrechen", plain.serialize(DefaultDialogHandler.cancelLabel(german, DialogRequest.DEFAULT_CANCEL)));
        assertEquals("Cancel", plain.serialize(DefaultDialogHandler.cancelLabel(english, DialogRequest.DEFAULT_CANCEL)));
        assertEquals("Close", plain.serialize(DefaultDialogHandler.cancelLabel(english, "Schließen")));
        assertEquals("Fertig", plain.serialize(DefaultDialogHandler.cancelLabel(english, "Fertig")));
    }
}
