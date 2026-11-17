package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMenuServiceTest {
    static final String DEFAULTS = """
            enabled: true
            limits:
              size:
                max: 16
                min: 2
              radius: 4
            rewards:
              - item: DIAMOND
                amount: 1
            weird: {}
            database:
              password: x
            """;
    @TempDir Path dir;
    int reloads;
    org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;
    @org.junit.jupiter.api.BeforeEach void up() { bukkit = mockStatic(org.bukkit.Bukkit.class); bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true); }
    @org.junit.jupiter.api.AfterEach void down() { bukkit.close(); }

    private Plugin plugin() throws Exception {
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Example");
        when(owner.getDataFolder()).thenReturn(dir.toFile());
        when(owner.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile("Example", "1.0", "example.Main"));
        when(owner.getLogger()).thenReturn(Logger.getLogger("t"));
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getResource(any())).thenAnswer(i -> "config.yml".equals(i.getArgument(0)) ? new ByteArrayInputStream(DEFAULTS.getBytes(StandardCharsets.UTF_8)) : null);
        YamlConfiguration live = new YamlConfiguration();
        live.loadFromString(DEFAULTS);
        when(owner.getConfig()).thenReturn(live);
        Files.writeString(dir.resolve("config.yml"), DEFAULTS);
        return owner;
    }
    private ConfigEditorService service(Plugin owner) {
        var registry = new SettingsRegistry(mock(Plugin.class), () -> null, p -> {});
        var service = new ConfigEditorService(() -> mock(Plugin.class), () -> registry, () -> null, new ConfigEditorService.Permissions() {
            @Override public void add(String node, String description, String parent) {}
            @Override public void remove(String node, String parent) {}
        });
        service.register(owner, "config.yml", () -> reloads++);
        return service;
    }
    private static Player admin() {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn("admin");
        when(p.hasPermission(any(String.class))).thenReturn(true);
        return p;
    }
    private ConfigEditorService.PluginState state(ConfigEditorService s, Plugin owner, Player p) { return s.visible(p).getFirst(); }

    @Test void categoriesHideSecretsAndNestFolders() throws Exception {
        var owner = plugin(); var s = service(owner); var p = admin();
        var categories = s.categories(state(s, owner, p));
        assertEquals(List.of("", "limits"), categories.stream().map(ConfigEditorService.Category::top).toList());
        var limits = categories.get(1).root();
        assertEquals(List.of("limits.radius"), limits.options());
        assertEquals("limits.size", limits.folders().getFirst().path());
    }
    @Test void changeResetAndChangedMarker() throws Exception {
        var owner = plugin(); var s = service(owner); var p = admin(); var st = state(s, owner, p);
        assertFalse(s.option(st, "config.yml", "limits.radius").changed());
        assertNull(s.change(p, st, "config.yml", "enabled", false));
        assertTrue(s.option(st, "config.yml", "enabled").changed());
        assertEquals(1, reloads);
        assertFalse(YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getBoolean("enabled"));
        s.reset(p, st, "config.yml", "enabled");
        assertFalse(s.option(st, "config.yml", "enabled").changed());
        assertTrue(YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getBoolean("enabled"));
    }
    @Test void structuresAreDetectedAndSaved() throws Exception {
        var owner = plugin(); var s = service(owner); var p = admin(); var st = state(s, owner, p);
        assertEquals(ConfigEditorService.OptionKind.STRUCTURE, s.option(st, "config.yml", "rewards").kind());
        var list = s.structure(st, "config.yml", "rewards");
        s.setStructure(p, st, "config.yml", "rewards", StructureOps.set(StructureOps.add(list, list.getFirst()), 1, "amount", 7));
        var saved = YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getMapList("rewards");
        assertEquals(2, saved.size());
        assertEquals(7, saved.get(1).get("amount"));
        assertTrue(s.option(st, "config.yml", "rewards").changed());
    }
    @Test void setupBatchWritesOnceAndRejectsSecrets() throws Exception {
        var owner = plugin(); var s = service(owner); var p = admin();
        assertTrue(s.applyBatch(p, "Example", Map.of(new SetupDefinition.Target("config.yml", "limits.radius"), 9, new SetupDefinition.Target("config.yml", "enabled"), false)));
        assertEquals(1, reloads);
        var saved = YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile());
        assertEquals(9, saved.getInt("limits.radius"));
        assertFalse(s.applyBatch(p, "Example", Map.of(new SetupDefinition.Target("config.yml", "limits.radius"), 1, new SetupDefinition.Target("config.yml", "database.password"), "y")));
        assertEquals(9, YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getInt("limits.radius"));
        assertFalse(s.applyBatch(p, "Example", Map.of(new SetupDefinition.Target("nope.yml", "a"), 1)));
    }
    @Test void noPermissionNoPlugin() throws Exception {
        var owner = plugin(); var s = service(owner);
        Player p = mock(Player.class);
        assertTrue(s.visible(p).isEmpty());
        assertFalse(s.applyBatch(p, "Example", Map.of()));
    }
}
