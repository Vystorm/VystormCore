package Vystorm.vystorm_core.poll.internal;

import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PollServiceTest {
    @TempDir Path folder;

    private Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return plugin;
    }

    @Test void givenExistingConfigWhenMergingThenCustomValuesAndBackupRemain() throws Exception {
        String original = "polls:\n  max-options: 3\nother: preserved\n";
        Files.writeString(folder.resolve("config.yml"), original);
        Plugin plugin = plugin();
        PollService.mergeDefaults(plugin);
        var config = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertEquals(3, config.getInt("polls.max-options"));
        assertEquals(600, config.getInt("polls.max-duration-seconds"));
        assertEquals("preserved", config.getString("other"));
        assertEquals(original, Files.readString(folder.resolve("config.yml.before-polls")));
        PollService.mergeDefaults(plugin);
        assertEquals(original, Files.readString(folder.resolve("config.yml.before-polls")));
    }

    @Test void givenBrokenConfigWhenMergingThenNoReplacementIsWritten() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "broken: [\n");
        PollService.mergeDefaults(plugin());
        assertFalse(Files.exists(folder.resolve("config.yml")));
        try (var files = Files.list(folder)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.yml.broken-")));
        }
    }

    @Test void givenPollCommandsWhenVotingClosingAndCancellingThenStateAndCleanupAreCorrect() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "{}");
        Plugin plugin = plugin();
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        Player player = mock(Player.class);
        var console = mock(ConsoleCommandSender.class);
        var config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getConsoleSender()).thenReturn(console);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(player.hasPermission("vystorm_core.poll")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenReturn(task);
        Lang lang = mock(Lang.class);
        when(lang.component(eq(console), eq("poll.option"), any(Object[].class))).thenReturn(Component.empty());
        when(lang.component(eq(player), eq("poll.option"), any(Object[].class))).thenReturn(Component.empty());
        Localization localization = mock(Localization.class);
        when(localization.core()).thenReturn(lang);
        AtomicReference<CommandSpec> tree = new AtomicReference<>();
        try (var languages = mockStatic(Localization.class); var commands = mockStatic(CoreCommands.class)) {
            languages.when(Localization::get).thenReturn(localization);
            commands.when(() -> CoreCommands.register(eq(plugin), eq(lang), any(CommandSpec.class)))
                    .thenAnswer(i -> { tree.set(i.getArgument(2)); return null; });
            var clock = new java.util.concurrent.atomic.AtomicLong(1000);
            try (PollService service = new PollService(plugin, clock::get)) {
                service.start(console, 30, "Q|A|B");
                var buttons = org.mockito.ArgumentCaptor.forClass(Component.class);
                verify(player, times(2)).sendMessage(buttons.capture());
                String id = buttons.getAllValues().getFirst().clickEvent().value().split(" ")[2];
                service.start(console, 30, "Other|C|D");
                verify(lang).send(console, "poll.running");
                CommandContext context = mock(CommandContext.class);
                when(context.sender()).thenReturn(player);
                when(context.player()).thenReturn(player);
                when(context.get("id")).thenReturn("stale-poll-id");
                when(context.get("option")).thenReturn(1);
                tree.get().children().get("vote").handler().execute(context);
                verify(lang).send(player, "poll.unavailable");
                when(context.get("id")).thenReturn(id);
                tree.get().children().get("vote").handler().execute(context);
                verify(lang).send(player, "poll.voted", "option", "A");
                var tick = org.mockito.ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).runTaskTimer(eq(plugin), tick.capture(), eq(20L), eq(20L));
                clock.set(31000);
                tick.getValue().run();
                tick.getValue().run();
                verify(lang).send(player, "poll.results", "question", "Q", "total", 1);
                service.start(console, 30, "Other|C|D");
                tree.get().children().get("cancel").handler().execute(context);
                verify(lang).send(player, "poll.cancelled");
                tree.get().children().get("results").handler().execute(context);
                verify(lang, times(2)).send(player, "poll.results", "question", "Q", "total", 1);
                service.start(console, 30, "Early|C|D");
                tree.get().children().get("close").handler().execute(context);
                verify(lang).send(player, "poll.results", "question", "Early", "total", 0);
                config.set("polls.enabled", false);
                service.start(console, 30, "Other|C|D");
                verify(lang).send(console, "poll.disabled");
                assertEquals("vystorm_core.admin", tree.get().children().get("start").permission());
                assertTrue(tree.get().children().get("vote").playerOnly());
            }
            verify(task).cancel();
        }
    }
}
