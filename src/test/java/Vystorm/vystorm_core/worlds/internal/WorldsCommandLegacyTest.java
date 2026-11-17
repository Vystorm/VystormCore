package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldsCommandLegacyTest {
    @TempDir Path dir;

    @Test
    void givenAdminAndUuid_whenLegacyArchiveRequested_thenDelegatesToEnvironment() {
        VisitEnv env = mock(VisitEnv.class);
        when(env.legacy(any(), eq("archive"))).thenReturn("archived");
        ManagedWorldRegistry.Host host = mock(ManagedWorldRegistry.Host.class);
        when(host.primaryThread()).thenReturn(true);
        VisitEngine visits = new VisitEngine(env, new ManagedWorldRegistry(host, dir.resolve("worlds")), new VisitSnapshotStore(dir.resolve("visits")));
        WorldsCommand command = new WorldsCommand(new ManagedWorldRegistry(host, dir.resolve("worlds-command")), visits, env);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("vystorm_core.admin")).thenReturn(true);
        UUID player = UUID.randomUUID();

        assertTrue(command.onCommand(sender, mock(Command.class), "vworlds", new String[]{"legacy", player.toString(), "archive"}));

        verify(env).legacy(player, "archive");
    }
}
