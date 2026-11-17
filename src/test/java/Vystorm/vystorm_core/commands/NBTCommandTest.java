package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class NBTCommandTest {
    @Test
    void givenPlayerWithoutPermission_whenCommandRuns_thenNothingIsTaggedAndUsageIsSuppressed() {
        Player player = mock(Player.class);
        when(player.hasPermission(NBT.PERMISSION)).thenReturn(false);
        try (var mainMock = mockStatic(main.class)) {
            assertTrue(new NBT().onCommand(player, mock(Command.class), "nbt", new String[]{"openMenu", "x"}));
            mainMock.verifyNoInteractions();
            verify(player, never()).getInventory();
        }
    }

    @Test
    void givenMissingArguments_whenCommandRuns_thenUsageIsShown() {
        Player player = mock(Player.class);
        when(player.hasPermission(NBT.PERMISSION)).thenReturn(true);
        assertFalse(new NBT().onCommand(player, mock(Command.class), "nbt", new String[]{"only"}));
    }

    @Test
    void givenConsole_whenCommandRuns_thenRejected() {
        CommandSender console = mock(CommandSender.class);
        assertTrue(new NBT().onCommand(console, mock(Command.class), "nbt", new String[]{"a", "b"}));
        verify(console).sendMessage("Players only.");
    }
}
