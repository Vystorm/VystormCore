package Vystorm.vystorm_core.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 0.66.1: a config.yml with a YAML error is never saved over (Vystorm Chat lost an admin's whole file this way). */
class BrokenConfigTest {
    static final String BROKEN = """
            # the admin's own settings
            channels:
              global:
                format: "<gray>{player}: {message}"
              staff
                permission: chat.staff
            keyword-alerts:
              enabled: true
            """;

    @TempDir Path dir;

    private JavaPlugin plugin(String text) throws Exception {
        Files.writeString(dir.resolve("config.yml"), text, StandardCharsets.UTF_8);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("t"));
        // what Bukkit's getConfig() is after a failed load: empty
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        return plugin;
    }

    @Test void givenAYamlError_whenAsked_thenTheLineAndColumnOfTheProblemAreNamed() throws Exception {
        plugin(BROKEN);
        String problem = YamlProblems.problem(dir.resolve("config.yml").toFile());
        assertNotNull(problem);
        assertTrue(problem.matches("line \\d+, column \\d+: .+"), problem);
        assertTrue(problem.startsWith("line 6,") || problem.startsWith("line 5,"), "the line of the missing colon or the next: " + problem);
        assertFalse(problem.contains("Exception"), problem);
        assertNull(YamlProblems.problem(dir.resolve("missing.yml").toFile()), "a missing file is no problem");
        Files.writeString(dir.resolve("ok.yml"), "a: 1\n");
        assertNull(YamlProblems.problem(dir.resolve("ok.yml").toFile()));
    }

    @Test void givenABrokenConfig_whenMigrated_thenTheFileIsByteIdenticalAndNothingIsSaved() throws Exception {
        JavaPlugin plugin = plugin(BROKEN);
        byte[] before = Files.readAllBytes(dir.resolve("config.yml"));
        int from = new ConfigMigrator("config-version", 3).step(2, c -> c.set("moved", true)).migrate(plugin);
        assertEquals(3, from, "nothing to migrate while the file is broken");
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("config.yml")));
        verify(plugin, never()).saveConfig();
        try (var files = Files.list(dir)) { assertEquals(1, files.count(), "no backup or other file written"); }
    }

    @Test void givenABrokenConfig_whenTheSettingsMenuSavesAChange_thenTheFileStaysByteIdentical() throws Exception {
        JavaPlugin plugin = plugin(BROKEN);
        when(plugin.getName()).thenReturn("Example");
        byte[] before = Files.readAllBytes(dir.resolve("config.yml"));
        int[] reloads = {0};
        var section = Vystorm.vystorm_core._uiMethods.settings.ConfigSettings.of(plugin)
                .toggle("keyword-alerts.enabled", "Alerts", "On or off")
                .section("example", "Example", "Test", "example.admin", p -> reloads[0]++);
        org.bukkit.entity.Player admin = mock(org.bukkit.entity.Player.class);
        section.afterSave().accept(admin);
        verify(plugin, never()).saveConfig();
        assertEquals(0, reloads[0], "nothing applied from an unsaved change");
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("config.yml")));
        plugin(YamlConfiguration.class.getSimpleName() + ": fine\n");
        section.afterSave().accept(admin);
        verify(plugin).saveConfig();
    }

    @Test void givenABrokenConfig_whenACommandWouldSaveIt_thenItIsRefusedAndTheSenderToldWhy() throws Exception {
        JavaPlugin plugin = plugin(BROKEN);
        CommandSender sender = mock(CommandSender.class);
        assertTrue(YamlProblems.refusedSave(sender, plugin));
        var said = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(sender).sendMessage(said.capture());
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(said.getValue());
        assertTrue(text.contains("config.yml") && text.contains("line "), text);
        plugin(YamlConfiguration.class.getSimpleName() + ": fine\n");
        assertFalse(YamlProblems.refusedSave(sender, plugin));
    }
}
