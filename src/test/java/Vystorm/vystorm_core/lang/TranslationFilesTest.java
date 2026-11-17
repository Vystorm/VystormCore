package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.lang.internal.TranslationFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Export/import round trip of the translator workflow and preservation of admin overrides on plugin updates. */
class TranslationFilesTest {
    private static final String EN = """
            greeting: "<green>Hello {player}!"
            menu:
              title: "Main menu"
              lore:
                - "<gray>First line"
                - "Second, with \\"quotes\\""
              count: "{count} items"
            """;
    private static final String ZH = """
            greeting: "<green>你好 {player}！"
            menu:
              title: "Main menu"
            """;

    @TempDir Path dir;

    private Lang bundle(Path folder) { return Lang.create("RoundTrip", Map.of("en", EN, "zh_cn", ZH), folder.toFile()); }

    @Test
    void givenBundle_whenEntriesListed_thenStatusPerKey() {
        Lang lang = bundle(dir.resolve("lang"));
        Map<String, String> status = new java.util.TreeMap<>();
        for (TranslationFiles.Entry e : TranslationFiles.entries(lang, "zh_cn")) status.put(e.key(), e.status());
        assertEquals(Map.of("greeting", "translated", "menu.title", "untranslated", "menu.lore", "missing", "menu.count", "missing"), status);
        Lang.Audit audit = lang.audit("zh_cn");
        assertEquals(List.of("menu.title"), audit.untranslated());
        assertEquals(50.0, audit.coverage(), 1e-9);
    }

    @Test
    void givenExport_whenEditedAndImported_thenMergedIntoOverrideFolderWithBackup() throws Exception {
        Path folder = dir.resolve("lang");
        Lang lang = bundle(folder);
        for (TranslationFiles.Format format : TranslationFiles.Format.values()) {
            TranslationFiles.Export export = TranslationFiles.export(lang, "zh_cn", format, dir.resolve("translations"));
            assertEquals("roundtrip.zh_cn." + format.extension, export.file().getFileName().toString());
            assertEquals(4, export.count());
            assertEquals(2, export.missing());
            // unchanged round trip: nothing is written, no backup
            TranslationFiles.Import same = TranslationFiles.importFile(export.file(), ns -> Optional.of(lang), null, null);
            assertEquals(0, same.added() + same.changed(), format.toString());
            assertNull(same.backup());
        }

        Path csv = dir.resolve("translations/roundtrip.zh_cn.csv");
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿key,status,source,translation"), "BOM + header");
        text = text.replace("menu.count,missing,{count} items,\"\"", "menu.count,missing,{count} items,\"{count} 个物品\"")
                .replace("menu.title,untranslated,Main menu,Main menu", "menu.title,untranslated,Main menu,\"主菜单, 新\"")
                .replace("\"<gray>First line\nSecond, with \"\"quotes\"\"\",\"\"", "\"<gray>First line\nSecond, with \"\"quotes\"\"\",\"<gray>第一行\n第二行，带“引号”\"")
                + "unknown.key,missing,x,\"未知\"\r\ngreeting,translated,x,\"你好！\"\r\n";
        Files.writeString(csv, text, StandardCharsets.UTF_8);

        TranslationFiles.Import result = TranslationFiles.importFile(csv, ns -> ns.equals("roundtrip") ? Optional.of(lang) : Optional.empty(), null, null);
        assertEquals("roundtrip", result.namespace());
        assertEquals("zh_cn", result.language());
        assertEquals(2, result.added());       // menu.count, menu.lore
        assertEquals(1, result.changed());     // menu.title
        assertEquals(Map.of("unknown.key", "unknown-key", "greeting", "placeholders"), result.skipped());
        assertNotNull(result.backup());
        assertTrue(Files.isRegularFile(result.backup()));
        assertEquals(folder.resolve("zh_cn.yml"), result.target());

        // the bundle was reloaded: new texts are live, the list stayed a list
        assertEquals("{count} 个物品".replace("{count}", "3"), lang.plain("zh_cn", "menu.count", "count", 3));
        assertEquals("主菜单, 新", lang.plain("zh_cn", "menu.title"));
        assertEquals(List.of("<gray>第一行", "第二行，带“引号”"), lang.translation("zh_cn", "menu.lore").orElseThrow());
        assertEquals("你好 Alex！", lang.plain("zh_cn", "greeting", "player", "Alex"));   // mismatching import skipped
        // the jar copy is untouched
        assertEquals("Main menu", lang.bundledTranslation("zh_cn", "menu.title").orElseThrow());

        // exporting again and importing the YAML round-trips the new state exactly
        TranslationFiles.Export again = TranslationFiles.export(lang, "zh_cn", TranslationFiles.Format.YAML, dir.resolve("translations"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(again.file().toFile());
        assertEquals("roundtrip", yaml.getString("plugin"));
        assertEquals(0, again.missing());
        TranslationFiles.Import noop = TranslationFiles.importFile(again.file(), ns -> Optional.of(lang), null, null);
        assertEquals(0, noop.added() + noop.changed());
        assertEquals(4, noop.unchanged());
    }

    @Test
    void givenBadNames_whenResolved_thenRejected() throws Exception {
        Path translations = Files.createDirectories(dir.resolve("translations"));
        Files.writeString(translations.resolve("a.zh_cn.yml"), "entries: []");
        assertEquals(translations.resolve("a.zh_cn.yml").toAbsolutePath().normalize(), TranslationFiles.resolve(translations, "a.zh_cn.yml"));
        for (String bad : List.of("../x.yml", "..\\x.yml", "/etc/passwd", ".hidden.yml", "sub/x.yml", "C:x.yml"))
            assertThrows(TranslationFiles.TranslationException.class, () -> TranslationFiles.resolve(translations, bad), bad);
        TranslationFiles.TranslationException missing = assertThrows(TranslationFiles.TranslationException.class, () -> TranslationFiles.resolve(translations, "none.yml"));
        assertEquals("file-missing", missing.reason);
        assertEquals(List.of("a.zh_cn.yml"), TranslationFiles.files(translations));
    }

    @Test
    void givenEnglishOrUnknownTarget_whenImported_thenRefused() throws Exception {
        Lang lang = bundle(dir.resolve("lang"));
        Path file = dir.resolve("roundtrip.en.yml");
        Files.writeString(file, "entries:\n  - key: greeting\n    translation: \"Hi {player}\"\n");
        assertEquals("source-language", assertThrows(TranslationFiles.TranslationException.class,
                () -> TranslationFiles.importFile(file, ns -> Optional.of(lang), null, null)).reason);
        assertEquals("plugin", assertThrows(TranslationFiles.TranslationException.class,
                () -> TranslationFiles.importFile(file, ns -> Optional.empty(), null, "zh_cn")).reason);
    }

    @Test
    void givenCsvWithQuotesAndLineBreaks_whenParsed_thenFieldsIntact() {
        assertEquals(List.of(List.of("a", "b,c", "d\"e", "f\ng"), List.of("x", "")),
                TranslationFiles.parseCsv("a,\"b,c\",\"d\"\"e\",\"f\ng\"\r\nx,\r\n"));
    }

    @Test
    void givenEditedOverride_whenPluginUpdated_thenEditKeptAndNewKeysAdded() throws Exception {
        Path folder = dir.resolve("upd");
        String jarV1 = "a: \"A one\"\nb: \"B one\"\n";
        Lang.create("Upd", Map.of("en", jarV1, "zh_cn", "a: \"甲\"\nb: \"乙\"\n"), folder.toFile());
        Path zh = folder.resolve("zh_cn.yml");
        Files.writeString(zh, Files.readString(zh).replace("甲", "管理员改的甲"));   // admin edits the override file
        // plugin update: changed jar texts and a new key
        Lang updated = Lang.create("Upd", Map.of("en", jarV1 + "c: \"C new\"\n", "zh_cn", "a: \"新甲\"\nb: \"新乙\"\nc: \"丙\"\n"), folder.toFile());
        assertEquals("管理员改的甲", updated.plain("zh_cn", "a"));   // edited text survives
        assertEquals("丙", updated.plain("zh_cn", "c"));           // new key arrives
        String disk = Files.readString(zh, StandardCharsets.UTF_8);
        assertTrue(disk.contains("管理员改的甲") && disk.contains("丙"), disk);
        assertTrue(Files.exists(folder.resolve("zh_cn.yml.before-update")));
        assertTrue(Files.readString(folder.resolve("en.yml")).contains("C new"));
    }
}
