package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.WebOpenResult;
import Vystorm.vystorm_core.web.WebServices;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** {@code /web [module]} opens like {@link WebServices#openOrLink} (overlay first, chat link otherwise). */
class WebCommandTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<WebServices> web;
    private Player player;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        web = mockStatic(WebServices.class);
        web.when(() -> WebServices.registered("settings")).thenReturn(true);
        web.when(() -> WebServices.openOrLink(any(), anyString(), anyString(), any())).thenReturn(WebOpenResult.OVERLAY);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alex");
    }

    @AfterEach void tearDown() {
        web.close();
        bukkit.close();
    }

    private boolean run(org.bukkit.command.CommandSender sender, String... args) {
        return new WebCommand().onCommand(sender, mock(Command.class), "web", args);
    }

    @Test void givenPlayerWhenWebModuleThenOpenOrLinkIsUsedInsteadOfAChatLink() {
        assertTrue(run(player, "settings"));
        web.verify(() -> WebServices.openOrLink(player, "settings", "", null));
        web.verify(() -> WebServices.sendLink(any(), any(), any()), never());
    }

    @Test void givenPlayerWhenPlainWebThenTheOverviewOpensThroughOpenOrLink() {
        assertTrue(run(player));
        web.verify(() -> WebServices.openOrLink(player, "", "", null));
        web.verify(() -> WebServices.sendLink(any(), any(), any()), never());
    }

    @Test void givenUnknownModuleThenNothingOpensAndThePlayerIsTold() {
        assertTrue(run(player, "nope"));
        web.verify(() -> WebServices.openOrLink(any(), anyString(), anyString(), any()), never());
        verify(player).sendMessage(any(Component.class));
    }

    @Test void givenConsoleWhenPlainWebThenOnlyUsageAndNoOpen() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertTrue(run(console));
        web.verify(() -> WebServices.openOrLink(any(), anyString(), anyString(), isNull()), never());
        verify(console).sendMessage(any(Component.class));
    }
}
