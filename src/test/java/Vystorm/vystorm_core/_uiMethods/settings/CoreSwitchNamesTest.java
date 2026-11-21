package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lifecycle.CoreSwitches;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * 0.66.1: {@code /vadmin features vystorm_core} showed names made from key paths ("Web › Client › Ui") and the
 * heading {@code vystorm_core}. Every switch Core lists from its real config.yml must reach the feature screen with a
 * readable name and a description from Core's language files, in all seven languages.
 */
class CoreSwitchNamesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final Pattern REF = Pattern.compile("\\{lang:vystorm_core:([a-z0-9_.-]+)}");

    @TempDir Path dir;
    private MockedStatic<org.bukkit.Bukkit> bukkit;

    @BeforeEach void up() { bukkit = mockStatic(org.bukkit.Bukkit.class); bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true); }
    @AfterEach void down() { bukkit.close(); }

    private Plugin core() throws Exception {
        byte[] config = Files.readAllBytes(Path.of("src/main/resources/config.yml"));
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("vystorm_core");
        when(owner.getDataFolder()).thenReturn(dir.toFile());
        when(owner.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile("vystorm_core", "1.0", "Vystorm.vystorm_core.main"));
        when(owner.getLogger()).thenReturn(Logger.getLogger("t"));
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getResource(any())).thenAnswer(i -> "config.yml".equals(i.getArgument(0)) ? new ByteArrayInputStream(config) : null);
        YamlConfiguration live = new YamlConfiguration();
        live.loadFromString(new String(config, java.nio.charset.StandardCharsets.UTF_8));
        when(owner.getConfig()).thenReturn(live);
        Files.write(dir.resolve("config.yml"), config);
        return owner;
    }

    private static Player admin() {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn("admin");
        when(p.hasPermission(any(String.class))).thenReturn(true);
        return p;
    }

    @Test void givenCoresOwnConfig_whenItsSwitchesAreDeclared_thenEveryListedSwitchHasANameAndDescriptionInAllLanguages() throws Exception {
        Plugin owner = core();
        var registry = new SettingsRegistry(mock(Plugin.class), () -> null, p -> {});
        var service = new ConfigEditorService(() -> mock(Plugin.class), () -> registry, () -> null, new ConfigEditorService.Permissions() {
            @Override public void add(String node, String description, String parent) {}
            @Override public void remove(String node, String parent) {}
        });
        service.register(owner, "config.yml", () -> {});
        // what main.declareSwitches does, through the same table
        CoreSwitches.declare(key -> "{lang:vystorm_core:" + key + "}", (path, label, description, declared) -> {
            if (declared) service.feature(owner, path, true);
            service.label(owner, path, label, description);
        });
        var state = service.visible(admin()).getFirst();
        var features = service.features(state);
        assertTrue(features.size() >= 30, "Core lists its switches: " + features.size());
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (var feature : features) {
                for (String ref : List.of(feature.label(), feature.description())) {
                    Matcher m = REF.matcher(ref);
                    assertTrue(m.matches(), feature.path() + " gets a language reference, not '" + ref + "'");
                    assertFalse(lang.getString(m.group(1), "").isBlank(), code + ": " + m.group(1) + " for " + feature.path());
                }
            }
        }
        assertTrue(features.stream().anyMatch(f -> f.path().equals("web.client.ui.enabled")), "the case from the report is listed");
    }

    @Test void givenPluginNames_whenShownAsHeading_thenCoreIsVystormCore() {
        Plugin core = mock(Plugin.class), chat = mock(Plugin.class);
        when(core.getName()).thenReturn("vystorm_core");
        when(chat.getName()).thenReturn("VystormChat");
        assertEquals("Vystorm Core", FeatureScreen.displayName(core));
        assertEquals("VystormChat", FeatureScreen.displayName(chat));
    }

    @Test void givenALongDescription_whenShortenedForTheFooter_thenItEndsAfterAWholeWord() {
        String text = "Sneak and left-click twice quickly into the air opens the main menu while you build or mine (off by default)";
        String shortened = FeatureScreen.shorten(text, 90);
        assertTrue(shortened.length() <= 90 && shortened.endsWith("…"), shortened);
        assertTrue(text.startsWith(shortened.substring(0, shortened.length() - 1)), shortened);
        assertEquals(' ', text.charAt(shortened.length() - 1), "cut at a space: " + shortened);
    }
}
