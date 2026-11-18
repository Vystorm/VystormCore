package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Core's own lang/en.yml and lang/de.yml: same keys, valid YAML, German texts kept, English used for unknown locales. */
class CoreLanguageFilesTest {
    private static Map<String, Object> load(String code) throws Exception {
        try (InputStream in = CoreLanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            assertNotNull(in, "lang/" + code + ".yml");
            return Lang.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), code);
        }
    }

    @Test
    void givenCoreFiles_whenCompared_thenSameKeysAndNoEmptyValues() throws Exception {
        Map<String, Object> en = load("en"), de = load("de");
        assertTrue(en.size() > 150, "en.yml parsed: " + en.size());
        Set<String> onlyEn = new TreeSet<>(en.keySet()); onlyEn.removeAll(de.keySet());
        Set<String> onlyDe = new TreeSet<>(de.keySet()); onlyDe.removeAll(en.keySet());
        assertEquals(Set.of(), onlyEn, "keys missing in de.yml");
        assertEquals(Set.of(), onlyDe, "keys missing in en.yml");
        en.forEach((k, v) -> assertFalse(v.toString().isBlank(), k));
    }

    /** YAML reads the bare keys on/off/yes/no as booleans, so "screen.classic.on" became "screen.classic.true" (0.65.0). */
    @Test
    void givenEveryLanguageFile_whenParsed_thenNoKeyWasReadAsBoolean() throws Exception {
        for (String code : new String[] { "en", "de", "fr", "it", "pt_br", "ru", "zh_cn" }) {
            Map<String, Object> texts = load(code);
            String raw;
            try (InputStream in = CoreLanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            java.util.regex.Matcher bare = java.util.regex.Pattern.compile("(?mi)^[ \\t]*(on|off|yes|no|true|false|y|n)[ \\t]*:").matcher(raw);
            assertFalse(bare.find(), () -> "lang/" + code + ".yml: quote the key in the line \"" + bare.group().strip() + "\", YAML reads it as a boolean");
            for (String key : new String[] { "screen.classic.on", "screen.classic.off", "trace.on", "trace.off", "network.off" })
                assertTrue(texts.containsKey(key), "lang/" + code + ".yml lacks " + key);
        }
    }

    @Test
    void givenClassicScreensSwitched_whenAnswered_thenATextNotTheKey() {
        Lang core = Localization.get().core();
        for (String language : new String[] { "en", "de" })
            for (String key : new String[] { "screen.classic.on", "screen.classic.off", "trace.on", "trace.off", "network.off" })
                assertNotEquals(key, PlainTextComponentSerializer.plainText().serialize(core.component(language, key, "player", "Alex")), language + " " + key);
    }

    @Test
    void givenCoreBundle_whenGermanOrOtherClient_thenGermanOrEnglish() {
        Lang core = Localization.get().core();
        Player german = mock(Player.class), unsupported = mock(Player.class);
        when(german.locale()).thenReturn(Locale.GERMANY);
        when(unsupported.locale()).thenReturn(Locale.of("es", "ES"));
        assertEquals("⌂ Hauptmenü", core.plain(german, "menu.home"));
        assertEquals("⌂ Main menu", core.plain(unsupported, "menu.home"));
        assertEquals("Welcome, Alex! Choose an area.",
                PlainTextComponentSerializer.plainText().serialize(core.component("en", "menu.directory.welcome", "player", "Alex")));
        assertEquals("Diese Einstellungen sind nicht verfügbar.", core.plain("de", "common.settings-unavailable"));
    }
}
