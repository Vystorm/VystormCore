package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class FeatureSwitchesTest {
    static final String DEFAULTS = """
            # Trains of several carts
            trains:
              enabled: true
              max-length: 8
            travel-gates:
              enabled: true
              # Gates cost energy
              energy:
                enabled: false
            # Protect containers when they are placed
            auto-protect: true
            debug: false
            hidden-feature:
              enabled: true
            database:
              password: ""
            """;

    @TempDir Path dir;
    private MockedStatic<org.bukkit.Bukkit> bukkit;
    private int reloads;

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

    @Test void givenConfigWhenListingFeaturesThenEnabledKeysAreFoundInFileOrder() throws Exception {
        var owner = plugin(); var s = service(owner); var st = s.visible(admin()).getFirst();
        var features = s.features(st);
        assertEquals(List.of("trains.enabled", "travel-gates.enabled", "travel-gates.energy.enabled", "auto-protect", "hidden-feature.enabled"),
                features.stream().map(ConfigEditorService.FeatureView::path).toList(), "enabled keys and top-level booleans, but not debug");
        assertEquals("Trains", features.get(0).label());
        assertEquals("Travel gates › Energy", features.get(2).label());
        assertEquals("Trains of several carts", features.get(0).description(), "section comment when the key has none");
        assertEquals("Gates cost energy", features.get(2).description());
        assertTrue(features.get(0).on());
        assertFalse(features.get(2).on());
    }

    @Test void givenDeclarationsWhenListingThenOtherNamesJoinAndExcludedOnesLeave() throws Exception {
        var owner = plugin(); var s = service(owner); var st = s.visible(admin()).getFirst();
        s.feature(owner, "debug", true);
        s.feature(owner, "hidden-feature.enabled", false);
        s.label(owner, "trains.enabled", "Cart trains", null);
        var features = s.features(st);
        assertEquals(List.of("trains.enabled", "travel-gates.enabled", "travel-gates.energy.enabled", "auto-protect", "debug"),
                features.stream().map(ConfigEditorService.FeatureView::path).toList());
        assertEquals("Cart trains", features.get(0).label());
        assertEquals("Auto protect", features.get(3).label());
        assertEquals("Protect containers when they are placed", features.get(3).description());
    }

    @Test void givenFeatureWhenToggledThenFileAndReloadHookFollow() throws Exception {
        var owner = plugin(); var s = service(owner); var p = admin(); var st = s.visible(p).getFirst();
        var energy = s.features(st).get(2);
        assertNull(s.toggleFeature(p, st, energy));
        assertTrue(YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getBoolean("travel-gates.energy.enabled"));
        assertTrue(s.features(st).get(2).on());
        assertEquals(1, reloads);
        assertNull(s.toggleFeature(p, st, s.features(st).get(2)));
        assertFalse(YamlConfiguration.loadConfiguration(dir.resolve("config.yml").toFile()).getBoolean("travel-gates.energy.enabled"));
    }

    @Test void givenWidgetIdsWhenParsedThenOnlyOwnPrefixAndRangeCount() {
        assertEquals(3, FeatureScreen.index("f3", 'f', 5));
        assertEquals(-1, FeatureScreen.index("f5", 'f', 5));
        assertEquals(-1, FeatureScreen.index("p3", 'f', 5));
        assertEquals(-1, FeatureScreen.index("footer", 'f', 5));
        assertEquals(-1, FeatureScreen.index(null, 'f', 5));
    }
}
