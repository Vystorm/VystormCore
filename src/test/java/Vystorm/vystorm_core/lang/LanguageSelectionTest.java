package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.internal.LanguageChoices;
import Vystorm.vystorm_core.lang.internal.LanguageFlags;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.DyeColor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The /language selection logic: offered languages, argument parsing, completion, coverage threshold, flags. */
class LanguageSelectionTest {
    @TempDir Path dir;
    private Plugin registered;

    @AfterEach
    void reset() {
        if (registered != null) Localization.get().unregister(registered);
        Localization.get().setMinCoverage(Localization.DEFAULT_MIN_COVERAGE);
        Localization.get().setFallbacks(null);
    }

    private static LanguageChoices choices() {
        Map<String, String> names = Map.of("en", "English", "de", "Deutsch", "zh_cn", "简体中文");
        Map<String, Double> coverage = Map.of("en", 100.0, "de", 99.7, "zh_cn", 100.0);
        return new LanguageChoices(Set.of("zh_cn", "de", "en"), names::get, coverage::get, Localization.get()::fallbackChain);
    }

    @Test
    void givenLanguages_whenOptionsListed_thenEnglishFirstThenByCodeWithNamesAndCoverage() {
        List<LanguageChoices.Option> options = choices().options();
        assertEquals(List.of("en", "de", "zh_cn"), options.stream().map(LanguageChoices.Option::code).toList());
        assertEquals("简体中文", options.get(2).name());
        assertEquals(99, options.get(1).percent());   // never rounded up to 100
        assertEquals(100, options.get(2).percent());
    }

    @Test
    void givenArguments_whenParsed_thenCodesAliasesFallbacksAndNamesResolve() {
        LanguageChoices c = choices();
        assertEquals(Optional.of("auto"), c.parse("auto"));
        assertEquals(Optional.of("auto"), c.parse("Automatisch"));
        assertEquals(Optional.of("auto"), c.parse("自动"));
        assertEquals(Optional.of("zh_cn"), c.parse("zh_cn"));
        assertEquals(Optional.of("zh_cn"), c.parse("zh-CN"));
        assertEquals(Optional.of("zh_cn"), c.parse("zh_tw"));     // fallback chain
        assertEquals(Optional.of("zh_cn"), c.parse("zh"));
        assertEquals(Optional.of("de"), c.parse("de_AT"));
        assertEquals(Optional.of("en"), c.parse("en_us"));
        assertEquals(Optional.of("de"), c.parse("deutsch"));     // own name
        assertEquals(Optional.of("zh_cn"), c.parse("简体中文"));
        assertEquals(Optional.empty(), c.parse("fr"));            // not offered: no silent English
        assertEquals(Optional.empty(), c.parse("Steve"));
        assertEquals(Optional.empty(), c.parse(" "));
        assertTrue(c.isLanguage("DE"));
        assertFalse(c.isLanguage("Notch"));
    }

    @Test
    void givenPrefix_whenCompleted_thenAutoAndMatchingCodes() {
        LanguageChoices c = choices();
        assertEquals(List.of("auto", "en", "de", "zh_cn"), c.complete(""));
        assertEquals(List.of("zh_cn"), c.complete("z"));
        assertEquals(List.of("zh_cn"), c.complete("ZH-"));
        assertEquals(List.of("auto"), c.complete("a"));
        assertEquals(List.of(), c.complete("x"));
    }

    private Plugin plugin(String name, Map<String, String> files) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getDataFolder()).thenReturn(dir.resolve(name).toFile());
        when(plugin.getResource(anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            String code = path.substring("lang/".length(), path.length() - ".yml".length());
            String yaml = files.get(code);
            return yaml == null ? null : new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        });
        return plugin;
    }

    @Test
    void givenPluginOnlyLanguage_whenCoverageBelowMinimum_thenNotOffered() {
        Localization s = Localization.get();
        StringBuilder en = new StringBuilder(), es = new StringBuilder();
        for (int i = 0; i < 2000; i++) en.append("k").append(i).append(": \"text ").append(i).append("\"\n");
        for (int i = 0; i < 3; i++) es.append("k").append(i).append(": \"texte ").append(i).append("\"\n");
        registered = plugin("SelectTest", Map.of("en", en.toString(), "es", es.toString()));
        s.register(registered);
        assertTrue(s.languages().contains("es"));
        assertTrue(s.coverage("es") < 5, "coverage " + s.coverage("es"));
        assertFalse(s.selectableLanguages().contains("es"));
        assertTrue(s.selectableLanguages().containsAll(Set.of("en", "de", "zh_cn")));   // Core ships them
        s.setMinCoverage(0);
        assertTrue(s.selectableLanguages().contains("es"));
        assertEquals(100.0, s.coverage("en"), 1e-9);
        assertTrue(s.coverage("zh_cn") > 20, "zh_cn in Core counts: " + s.coverage("zh_cn"));
    }

    @Test
    void givenCodes_whenFlagsChosen_thenKnownFlagsAndGlobeFallback() {
        assertEquals(DyeColor.RED, LanguageFlags.flag("zh_cn").base());
        assertEquals(DyeColor.RED, LanguageFlags.flag("de").base());
        assertEquals(DyeColor.RED, LanguageFlags.flag("de_at").base());   // language part
        assertEquals(DyeColor.BLUE, LanguageFlags.flag("en").base());
        assertEquals(DyeColor.LIGHT_BLUE, LanguageFlags.flag("xx").base());
        assertFalse(LanguageFlags.flag("ja").layers().isEmpty());
    }

    @Test
    void givenCodes_whenNativeNameAsked_thenOwnFileNameOrJavaName() {
        assertEquals("简体中文", CoreLanguage.nativeName("zh_cn"));
        assertEquals("Deutsch", CoreLanguage.nativeName("de"));
        assertEquals("Français", CoreLanguage.nativeName("fr"));
        assertEquals("English", CoreLanguage.nativeName("EN"));
    }

    @Test
    void givenEvent_whenCreated_thenValuesAndChangeFlag() {
        org.bukkit.entity.Player p = mock(org.bukkit.entity.Player.class);
        PlayerLanguageChangeEvent e = new PlayerLanguageChangeEvent(p, "en", "zh_cn", "zh_cn", PlayerLanguageChangeEvent.Cause.COMMAND);
        assertTrue(e.isLanguageChanged());
        assertEquals("zh_cn", e.getLanguage());
        assertEquals(PlayerLanguageChangeEvent.Cause.COMMAND, e.getCause());
        PlayerLanguageChangeEvent same = new PlayerLanguageChangeEvent(p, "de", "de", null, null);
        assertFalse(same.isLanguageChanged());
        assertEquals("auto", same.getChoice());
        assertEquals(PlayerLanguageChangeEvent.Cause.API, same.getCause());
        assertNotNull(PlayerLanguageChangeEvent.getHandlerList());
    }
}
