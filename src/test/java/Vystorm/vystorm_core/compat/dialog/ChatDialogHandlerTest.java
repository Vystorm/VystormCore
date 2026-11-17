package Vystorm.vystorm_core.compat.dialog;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ChatDialogHandlerTest {
    @Test void givenChatSessionWhenReplayedExpiredOrReleasedThenCallbacksRunAtMostOnce() {
        var player = mock(org.bukkit.entity.Player.class);
        var owner = mock(org.bukkit.plugin.Plugin.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        var sent = new java.util.ArrayList<Component>();
        var expiry = new java.util.ArrayList<Runnable>();
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var cancelled = new java.util.concurrent.atomic.AtomicInteger();
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getServer()).thenReturn(mock(org.bukkit.Server.class));
        when(owner.isEnabled()).thenReturn(true);
        doAnswer(call -> { sent.add(call.getArgument(0)); return null; }).when(player).sendMessage(any(Component.class));
        when(scheduler.runTaskLater(any(org.bukkit.plugin.Plugin.class), any(Runnable.class), anyLong()))
                .thenAnswer(call -> { expiry.add(call.getArgument(1)); return mock(org.bukkit.scheduler.BukkitTask.class); });
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class); var core = mockStatic(Vystorm.vystorm_core.main.class)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            core.when(Vystorm.vystorm_core.main::getPlugin).thenReturn(owner);
            ChatDialogHandler handler = new ChatDialogHandler();
            DialogRequest request = new DialogRequest(Component.text("Form"), List.of(), List.of(),
                    List.of(new DialogButton(Component.text("Go"), (p, r) -> submitted.incrementAndGet())),
                    p -> true, cancelled::incrementAndGet);
            handler.open(owner, player, request);
            String command = sent.stream().filter(c -> c.clickEvent() != null).findFirst().orElseThrow().clickEvent().value();
            var click = new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, command);
            handler.command(click);
            assertTrue(click.isCancelled());
            handler.command(new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, command));
            expiry.getFirst().run();
            assertEquals(1, submitted.get());
            assertEquals(0, cancelled.get());
            handler.open(owner, player, request);
            expiry.getLast().run();
            expiry.getLast().run();
            assertEquals(1, cancelled.get());
            handler.open(owner, player, request);
            handler.removeAll(owner);
            expiry.getLast().run();
            assertEquals(1, cancelled.get());
        }
    }

    @Test void givenOutOfRangeOrNonfiniteNumberWhenSubmittedThenRejected() {
        DialogInput input = DialogInput.numberRange("v", Component.empty(), 1, 10).build();
        assertEquals(5f, ChatDialogHandler.parse(input, "5"));
        for (String value : List.of("NaN", "Infinity", "0", "11", "text"))
            assertThrows(IllegalArgumentException.class, () -> ChatDialogHandler.parse(input, value));
    }

    @Test void givenChoiceTextAndBooleanWhenSubmittedThenConstraintsAreChecked() {
        DialogInput choice = DialogInput.singleOption("v", Component.empty(),
                List.of(SingleOptionDialogInput.OptionEntry.create("a", Component.empty(), true))).build();
        assertEquals("a", ChatDialogHandler.parse(choice, "a"));
        assertThrows(IllegalArgumentException.class, () -> ChatDialogHandler.parse(choice, "forged"));
        DialogInput text = DialogInput.text("v", Component.empty()).maxLength(3).build();
        assertEquals("abc", ChatDialogHandler.parse(text, "abc"));
        assertThrows(IllegalArgumentException.class, () -> ChatDialogHandler.parse(text, "abcd"));
        DialogInput bool = DialogInput.bool("v", Component.empty()).build();
        assertEquals(true, ChatDialogHandler.parse(bool, "true"));
        assertThrows(IllegalArgumentException.class, () -> ChatDialogHandler.parse(bool, "maybe"));
    }
}
