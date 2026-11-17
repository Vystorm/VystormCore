package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fallback chain (zh_tw → zh_cn → en, de_at → de → en), per-key fallback, server language and force. */
class FallbackChainTest {
    private static final String EN = """
            a: "A en"
            b: "B en"
            c: "C en"
            """;
    private static final String ZH_CN = """
            a: "A 简"
            b: "B 简"
            """;
    private static final String ZH_TW = """
            a: "A 繁"
            """;
    private static final String DE = """
            a: "A de"
            """;

    @AfterEach
    void reset() {
        Localization service = Localization.get();
        service.setFallbacks(null);
        service.setServerLanguage("en");
        service.setForced(false);
        service.setDefaultLanguage(Localization.AUTO);
    }

    private static Lang lang() { return Lang.create("ChainTest", Map.of("en", EN, "zh_cn", ZH_CN, "zh_tw", ZH_TW, "de", DE)); }

    private static Player player(Locale locale) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    @Test
    void givenDefaults_whenChainsBuilt_thenRegionalAndChineseVariantsFallBackInOrder() {
        Localization s = Localization.get();
        assertEquals(List.of("zh_tw", "zh_cn", "zh", "en"), s.fallbackChain("zh_tw"));
        assertEquals(List.of("zh_hk", "zh_tw", "zh_cn", "zh", "en"), s.fallbackChain("zh-HK"));
        assertEquals(List.of("de_at", "de", "en"), s.fallbackChain("de_AT"));
        assertEquals(List.of("de_ch", "de", "en"), s.fallbackChain("de_ch"));
        assertEquals(List.of("en_us", "en"), s.fallbackChain("en_us"));
        assertEquals(List.of("en"), s.fallbackChain("en"));
        assertEquals(List.of("zh", "zh_cn", "en"), s.fallbackChain("zh"));
    }

    @Test
    void givenPartialTranslations_whenKeysLookedUp_thenEachKeyFallsBackAlongTheChain() {
        Lang lang = lang();
        Player tw = player(Locale.TRADITIONAL_CHINESE);
        assertEquals("zh_tw", lang.language(tw));
        assertEquals("A 繁", lang.plain(tw, "a"));   // own text
        assertEquals("B 简", lang.plain(tw, "b"));   // zh_cn fills the gap
        assertEquals("C en", lang.plain(tw, "c"));   // then English
        assertEquals("c-missing", lang.plain(tw, "c-missing"));
        Player at = player(Locale.forLanguageTag("de-AT"));
        assertEquals("de", lang.language(at));
        assertEquals("A de", lang.plain(at, "a"));
        assertEquals("B en", lang.plain(at, "b"));
    }

    @Test
    void givenLanguageOnlyInFallback_whenPicked_thenFallbackFileChosen() {
        Lang onlySimplified = Lang.create("ChainTest2", Map.of("en", EN, "zh_cn", ZH_CN));
        assertEquals("zh_cn", onlySimplified.language(player(Locale.TRADITIONAL_CHINESE)));
        assertEquals("zh_cn", onlySimplified.resolveLanguage("zh_hk"));
        assertEquals("zh_cn", onlySimplified.resolveLanguage("zh"));
        assertEquals("en", onlySimplified.resolveLanguage("fr"));
    }

    @Test
    void givenConfiguredFallbacks_whenApplied_thenOwnStepsAndRemovalsWork() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                language:
                  default: auto
                  fallback:
                    fr_ca: fr
                    zh_tw: none
                    lb: de
                """);
        Localization s = Localization.get();
        s.configure(config);
        assertEquals(List.of("fr_ca", "fr", "en"), s.fallbackChain("fr_ca"));
        assertEquals(List.of("zh_tw", "zh", "zh_cn", "en"), s.fallbackChain("zh_tw"));
        assertEquals(List.of("lb", "de", "en"), s.fallbackChain("lb"));
        assertEquals("A de", lang().plain(player(Locale.forLanguageTag("lb-LU")), "a"));
    }

    @Test
    void givenCycle_whenChainBuilt_thenCutWithoutLoop() {
        Localization s = Localization.get();
        s.setFallbacks(Map.of("aa", "bb", "bb", "aa"));
        assertEquals(List.of("aa", "bb", "en"), s.fallbackChain("aa"));
    }

    @Test
    void givenServerLanguage_whenClientLanguageHasNoFile_thenServerLanguageUsed() {
        Localization s = Localization.get();
        s.setServerLanguage("de");
        Lang lang = lang();
        assertEquals("de", lang.language(player(Locale.FRANCE)));
        assertEquals("de", lang.language(mock(ConsoleCommandSender.class)));
        assertEquals("zh_cn", lang.language(player(Locale.SIMPLIFIED_CHINESE)));
        assertEquals("en", lang.language(player(Locale.US)));   // English clients keep English
    }

    @Test
    void givenForce_whenPlayerChoseOwnLanguage_thenEveryoneGetsServerLanguage() {
        Localization s = Localization.get();
        Lang lang = lang();
        Player chinese = player(Locale.SIMPLIFIED_CHINESE);
        s.setPersonalLanguage(chinese.getUniqueId(), "de");
        assertEquals("de", lang.language(chinese));
        s.setServerLanguage("zh_cn");
        s.setForced(true);
        assertEquals("zh_cn", lang.language(chinese));
        assertEquals("zh_cn", lang.language(player(Locale.GERMANY)));
        assertEquals("zh_cn", lang.languageFor(Locale.GERMANY));
        assertEquals("zh_cn", lang.languageFor(chinese.getUniqueId(), List.of("de")));
        s.setForced(false);
        assertEquals("de", lang.language(chinese));
        s.setPersonalLanguage(chinese.getUniqueId(), null);
    }

    @Test
    void givenPartialBundle_whenCoverageAsked_thenPercentOfEnglishKeys() {
        Lang lang = lang();
        assertEquals(100.0, lang.coverage("en"), 1e-9);
        assertEquals(200.0 / 3, lang.coverage("zh_cn"), 1e-9);
        assertEquals(100.0 / 3, lang.coverage("zh_tw"), 1e-9);
        assertEquals(0.0, lang.coverage("fr"), 1e-9);
        assertEquals(new Lang.Counts(3, 2), lang.counts("zh_cn"));
        assertEquals(Set3.of("a", "b", "c"), lang.keys());
    }

    private static final class Set3 { static java.util.Set<String> of(String... v) { return new java.util.TreeSet<>(List.of(v)); } }
}
