package Vystorm.vystorm_core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class TradeChatCommandTest {
    private static final Command COMMAND = mock(Command.class);

    @Test
    void givenPlayerWithoutSendPermission_whenCommandRuns_thenDoesNotDispatchEvent() {
        Player sender = player("Trader", false, true);
        PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);

            new TradeChatCommand(() -> 0).onCommand(sender, COMMAND, "vtrade", new String[]{"hello"});

            verifyNoInteractions(manager);
        }
    }

    @Test
    void givenCancelledEvent_whenCommandRuns_thenNoPlayerReceivesMessage() {
        Player sender = player("Trader", true, true);
        Player recipient = player("Buyer", false, true);
        PluginManager manager = mock(PluginManager.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ChannelChatEvent) invocation.getArgument(0)).setCancelled(true);
            return null;
        }).when(manager).callEvent(any(ChannelChatEvent.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(sender, recipient));

            new TradeChatCommand(() -> 0).onCommand(sender, COMMAND, "vtrade", new String[]{"hello"});

            verify(sender, never()).sendMessage(any(Component.class));
            verify(recipient, never()).sendMessage(any(Component.class));
        }
    }

    @Test
    void givenAllowedTradeMessage_whenCommandRuns_thenSendsToReceiversAndEchoesToSender() {
        Player sender = player("Trader", true, false);
        Player recipient = player("Buyer", false, true);
        Player unrelated = player("Observer", false, false);
        PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(sender, recipient, unrelated));

            new TradeChatCommand(() -> 0).onCommand(sender, COMMAND, "vtrade", new String[]{"buy", "offer"});

            verify(manager).callEvent(any(ChannelChatEvent.class));
            assertTradeDelivery(sender, "Trader: buy offer");
            assertTradeDelivery(recipient, "Trader: buy offer");
            verify(unrelated, never()).sendMessage(any(Component.class));
        }
    }

    @Test
    void givenRecentMessage_whenCooldownExpiresOrPlayerQuits_thenCanSendAgain() {
        Player sender = player("Trader", true, true);
        PluginManager manager = mock(PluginManager.class);
        AtomicLong now = new AtomicLong(0);
        TradeChatCommand command = new TradeChatCommand(now::get);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(sender));

            command.onCommand(sender, COMMAND, "vtrade", new String[]{"first"});
            now.set(1_999_999_999L);
            command.onCommand(sender, COMMAND, "vtrade", new String[]{"too", "soon"});
            verify(manager, org.mockito.Mockito.times(1)).callEvent(any(ChannelChatEvent.class));

            now.set(2_000_000_000L);
            command.onCommand(sender, COMMAND, "vtrade", new String[]{"ready"});
            verify(manager, org.mockito.Mockito.times(2)).callEvent(any(ChannelChatEvent.class));

            command.onQuit(new PlayerQuitEvent(sender, ""));
            command.onCommand(sender, COMMAND, "vtrade", new String[]{"after", "quit"});
            verify(manager, org.mockito.Mockito.times(3)).callEvent(any(ChannelChatEvent.class));
        }
    }

    @Test
    void givenControlCharacterOrOverlongMessage_whenCommandRuns_thenDoesNotDispatchEvent() {
        Player sender = player("Trader", true, true);
        PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            TradeChatCommand command = new TradeChatCommand(() -> 0);

            command.onCommand(sender, COMMAND, "vtrade", new String[]{"line\nbreak"});
            command.onCommand(sender, COMMAND, "vtrade", new String[]{"x".repeat(241)});

            verifyNoInteractions(manager);
        }
    }

    private static Player player(String name, boolean canSend, boolean canReceive) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(TradeChatCommand.SEND_PERMISSION)).thenReturn(canSend);
        when(player.hasPermission(TradeChatCommand.RECEIVE_PERMISSION)).thenReturn(canReceive);
        return player;
    }

    private static void assertTradeDelivery(Player player, String expectedText) {
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        Component delivered = captor.getValue();
        assertEquals("vystorm.chat.channel.trade", ((TranslatableComponent) delivered).key());
        org.junit.jupiter.api.Assertions.assertTrue(PlainTextComponentSerializer.plainText().serialize((Component)((TranslatableComponent)delivered).arguments().getFirst().value()).endsWith(expectedText));
    }
}
