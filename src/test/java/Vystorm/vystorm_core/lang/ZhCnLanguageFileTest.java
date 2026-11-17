package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Core's lang/zh_cn.yml: key parity with en.yml, identical placeholders and formatting tags, really Chinese, auto-detected. */
class ZhCnLanguageFileTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");
    private static final Pattern TAG = Pattern.compile("</?[a-z_#!][^<>\\s]*>|[&§][0-9a-fk-or]");

    @AfterEach
    void reset() { Localization.get().setDefaultLanguage(Localization.AUTO); }

    private static Map<String, Object> load(String code) throws Exception {
        try (InputStream in = ZhCnLanguageFileTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            assertNotNull(in, "lang/" + code + ".yml");
            return Lang.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), code);
        }
    }

    private static List<String> all(Pattern p, Object value) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(String.valueOf(value));
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void givenZhCn_whenComparedWithEnglish_thenSameKeys() throws Exception {
        Map<String, Object> en = load("en"), zh = load("zh_cn");
        Set<String> missing = new TreeSet<>(en.keySet()); missing.removeAll(zh.keySet());
        Set<String> extra = new TreeSet<>(zh.keySet()); extra.removeAll(en.keySet());
        assertEquals(Set.of(), missing, "keys missing in zh_cn.yml");
        assertEquals(Set.of(), extra, "keys only in zh_cn.yml");
        zh.forEach((k, v) -> assertFalse(String.valueOf(v).isBlank() && !String.valueOf(en.get(k)).isBlank(), k));
    }

    @Test
    void givenZhCn_whenComparedWithEnglish_thenPlaceholdersIdentical() throws Exception {
        Map<String, Object> en = load("en"), zh = load("zh_cn");
        List<String> wrong = new ArrayList<>();
        for (String key : en.keySet()) {
            List<String> a = new ArrayList<>(all(PLACEHOLDER, en.get(key))), b = new ArrayList<>(all(PLACEHOLDER, zh.get(key)));
            a.sort(null); b.sort(null);
            if (!a.equals(b)) wrong.add(key + ": " + a + " vs " + b);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void givenZhCn_whenComparedWithEnglish_thenFormattingTagsUntouched() throws Exception {
        Map<String, Object> en = load("en"), zh = load("zh_cn");
        List<String> wrong = new ArrayList<>();
        for (String key : en.keySet())
            if (!all(TAG, en.get(key)).equals(all(TAG, zh.get(key)))) wrong.add(key + ": " + all(TAG, en.get(key)) + " vs " + all(TAG, zh.get(key)));
        assertEquals(List.of(), wrong, "MiniMessage tags and & codes must stay in the same order");
        // list values stay lists with the same number of lines
        for (String key : en.keySet())
            if (en.get(key) instanceof List<?> l) assertEquals(l.size(), ((List<?>) zh.get(key)).size(), key);
    }

    @Test
    void givenZhCn_whenEnglishTextHasWords_thenTranslationIsChinese() throws Exception {
        Map<String, Object> en = load("en"), zh = load("zh_cn");
        Set<String> latinOnly = Set.of(
                // command syntax and pure formatting/brand lines stay as they are
                "settings.profile.usage", "region.usage", "web.link-usage", "web.console-usage", "admin.usage", "admin.list-line",
                "worlds.admin.usage", "permissions.usage", "permissions.line", "config.usage", "config-history.usage", "config-history.entry",
                "news.usage", "news.list-line", "news.post-meta", "loot.usage", "admin.language-usage", "review.link",
                "menu.directory.group-title", "web.ui.settings.range", "web.ui.settings.rejected", "settings.error",
                "updates.line-newer", "chat.coordinates", "web.ui.app.page.core", "menu.access.line");
        List<String> notChinese = new ArrayList<>();
        for (String key : en.keySet()) {
            String source = String.valueOf(en.get(key)).replaceAll("<[^>]*>|\\{[^}]*}|/[a-z]+[^ ]*", "");
            if (!source.codePoints().anyMatch(Character::isLetter) || latinOnly.contains(key)) continue;
            boolean cjk = String.valueOf(zh.get(key)).codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
            if (!cjk) notChinese.add(key + " = " + zh.get(key));
        }
        assertTrue(notChinese.size() <= 0, "not translated: " + notChinese);
    }

    @Test
    void givenCoreBundle_whenZhCnAudited_thenNothingMissingAndNoBrokenPlaceholdersOrTags() {
        Lang.Audit audit = Localization.get().core().audit("zh_cn");
        assertEquals(List.of(), audit.missing());
        assertEquals(List.of(), audit.placeholderMismatch());
        assertEquals(List.of(), audit.unfilledTags());
        assertEquals(100.0, Localization.get().core().coverage("zh_cn"), 1e-9);
        Lang.Audit de = Localization.get().core().audit("de");
        assertEquals(List.of(), de.placeholderMismatch());
        assertEquals(List.of(), de.unfilledTags());
    }

    private static Player player(Locale locale) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    @Test
    void givenChineseClient_whenCoreTextRequested_thenSimplifiedChineseAutoDetected() {
        Lang core = Localization.get().core();
        assertTrue(core.languages().contains("zh_cn"));
        assertEquals("zh_cn", core.language(player(Locale.SIMPLIFIED_CHINESE)));
        assertEquals("⌂ 主菜单", core.plain(player(Locale.SIMPLIFIED_CHINESE), "menu.home"));
        assertEquals("欢迎，Alex！请选择一个区域。", core.plain("zh_cn", "menu.directory.welcome", "player", "Alex"));
        // Traditional Chinese clients fall back to Simplified Chinese, not English
        assertEquals("zh_cn", core.language(player(Locale.TRADITIONAL_CHINESE)));
        assertEquals("zh_cn", core.language(player(Locale.forLanguageTag("zh-HK"))));
        assertEquals("简体中文", core.raw("zh_cn", "language.name"));
    }
}
