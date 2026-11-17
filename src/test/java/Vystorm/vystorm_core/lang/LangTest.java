package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangTest {
    private static final String EN = """
            greeting: "Hello {player}!"
            shop:
              bought: "<green>You bought {amount}x {item}."
              only-en: "English only"
            lore:
              - "<gray>First line"
              - "Second {n}"
            legacy: "&cRed &lbold"
            """;
    private static final String DE = """
            greeting: "Hallo {player}!"
            shop:
              bought: "<green>Du hast {amount}x {item} gekauft."
            """;

    @TempDir Path dir;

    @AfterEach
    void reset() { Localization.get().setDefaultLanguage(Localization.AUTO); }

    private static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    private static Player player(Locale locale) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private Lang lang() { return Lang.create("TestPlugin", Map.of("en", EN, "de", DE)); }

    @Test
    void givenGermanClient_whenAuto_thenGermanTextWithPlaceholders() {
        Lang lang = lang();
        Player p = player(Locale.of("de", "AT"));
        assertEquals("de", lang.language(p));
        assertEquals("Du hast 3x Brot gekauft.", plain(lang.component(p, "shop.bought", "amount", 3, "item", "Brot")));
        assertEquals(NamedTextColor.GREEN, lang.component(p, "shop.bought").color());
    }

    @Test
    void givenUnknownClientLocale_whenAuto_thenEnglish() {
        Lang lang = lang();
        assertEquals("en", lang.language(player(Locale.of("fr", "FR"))));
        assertEquals("en", lang.language(player(null)));
    }

    @Test
    void givenConsole_whenAutoOrFixed_thenEnglishOrFixedCode() {
        Lang lang = lang();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertEquals("en", lang.language(console));
        Localization.get().setDefaultLanguage("de");
        assertEquals("de", lang.language(console));
        assertEquals("de", lang.language(player(Locale.of("en", "US"))));
    }

    @Test
    void givenPersonalChoiceOrBundleOverride_whenResolved_thenPersonalWinsOverOverride() {
        Lang lang = lang();
        Player p = player(Locale.of("de", "DE"));
        lang.setLanguage("en");
        assertEquals("en", lang.language(p));
        Localization.get().setPersonalLanguage(p.getUniqueId(), "de");
        try {
            assertEquals("de", lang.language(p));
        } finally {
            Localization.get().setPersonalLanguage(p.getUniqueId(), null);
        }
        lang.setLanguage("auto");
        assertEquals("de", lang.language(p));
    }

    @Test
    void givenMissingKey_whenLookedUp_thenFallsBackToEnglishThenKey() {
        Lang lang = lang();
        assertEquals("English only", lang.plain("de", "shop.only-en"));
        assertEquals("no.such.key", lang.plain("de", "no.such.key"));
        assertEquals("no.such.key", lang.raw("xx", "no.such.key"));
        assertEquals("", lang.plain("de", null));
    }

    @Test
    void givenDiskFile_whenKeyEdited_thenDiskWinsAndMissingKeysUseBundledSameLanguage() throws Exception {
        File folder = dir.resolve("lang").toFile();
        Lang lang = Lang.create("TestPlugin", Map.of("en", EN, "de", DE), folder);
        assertTrue(new File(folder, "en.yml").isFile(), "bundled files are copied");
        assertTrue(new File(folder, "de.yml").isFile());
        // Admin edits de.yml and drops shop.bought; adds a new language.
        Files.writeString(folder.toPath().resolve("de.yml"), "greeting: \"Servus {player}!\"\n", StandardCharsets.UTF_8);
        Files.writeString(folder.toPath().resolve("fr.yml"), "greeting: \"Salut {player} !\"\n", StandardCharsets.UTF_8);
        lang.reload();
        assertEquals("Servus Alex!", lang.plain("de", "greeting", "player", "Alex"));
        assertEquals("Du hast 1x A gekauft.", lang.plain("de", "shop.bought", "amount", 1, "item", "A"));
        assertEquals("Salut Alex !", lang.plain("fr", "greeting", "player", "Alex"));
        assertEquals("You bought 1x A.", lang.plain("fr", "shop.bought", "amount", 1, "item", "A"));
        assertEquals("fr", lang.language(player(Locale.of("fr", "CA"))));
        // An existing admin file is never overwritten.
        lang.reload();
        assertTrue(Files.readString(folder.toPath().resolve("de.yml")).contains("Servus"));
    }

    @Test
    void givenBrokenDiskFile_whenLoaded_thenBundledTextsStillWork() throws Exception {
        File folder = dir.resolve("broken").toFile();
        Lang lang = Lang.create("TestPlugin", Map.of("en", EN, "de", DE), folder);
        Files.writeString(folder.toPath().resolve("de.yml"), "greeting: [unclosed\n", StandardCharsets.UTF_8);
        lang.reload();
        assertEquals("Hallo X!", lang.plain("de", "greeting", "player", "X"));
    }

    @Test
    void givenPlaceholderValues_whenFormatted_thenValuesAreLiteralAndUnknownPlaceholdersStay() {
        Lang lang = lang();
        assertEquals("Hello <red>Bob!", lang.plain("en", "greeting", "player", "<red>Bob"));
        assertEquals("Hello {player}!", lang.plain("en", "greeting"));
        assertEquals("Hello {player}!", lang.plain("en", "greeting", "other", "x"));
        assertEquals("Hello Bob!", lang.plain("en", "greeting", "player", "Bob", "dangling"));
        Component value = Component.text("Gold", NamedTextColor.GOLD);
        Component c = lang.component("en", "greeting", "player", value);
        assertEquals("Hello Gold!", plain(c));
    }

    @Test
    void givenLegacyCodes_whenFormatted_thenConvertedToStyles() {
        Component c = lang().component("en", "legacy");
        assertEquals("Red bold", plain(c));
        assertEquals("§cRed §lbold", lang().text("en", "legacy"));
    }

    @Test
    void givenListValue_whenLines_thenOneComponentPerLine() {
        List<Component> lines = lang().lines("en", "lore", "n", 2);
        assertEquals(2, lines.size());
        assertEquals("Second 2", plain(lines.get(1)));
        assertEquals("<gray>First line\nSecond {n}", lang().raw("en", "lore"));
        assertEquals(1, lang().lines("en", "greeting").size());
    }

    @Test
    void givenLocaleCodes_whenNormalized_thenLowerUnderscoreAndLanguagePart() {
        assertEquals("de_de", Localization.normalize("de-DE"));
        assertEquals("de", Localization.languagePart("de_AT"));
        assertEquals("en", Localization.languagePart("en"));
        assertEquals("", Localization.normalize(null));
    }

    @Test
    void givenRegisteredBundle_whenRefOrTranslatableRendered_thenViewerLanguage() {
        Localization service = Localization.get();
        org.bukkit.plugin.Plugin plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getName()).thenReturn("RefPlugin");
        when(plugin.getDataFolder()).thenReturn(dir.resolve("RefPlugin").toFile());
        when(plugin.getResource("lang/en.yml")).thenAnswer(i -> new java.io.ByteArrayInputStream(EN.getBytes(StandardCharsets.UTF_8)));
        when(plugin.getResource("lang/de.yml")).thenAnswer(i -> new java.io.ByteArrayInputStream(DE.getBytes(StandardCharsets.UTF_8)));
        Lang lang = service.register(plugin);
        try {
            assertSame(lang, service.register(plugin));
            Player de = player(Locale.of("de", "DE"));
            String ref = lang.ref("greeting");
            assertTrue(Localization.isRef(ref));
            assertEquals("Hallo {player}!", service.resolve(de, ref));
            assertEquals("plain title", service.resolve(de, "plain title"));
            assertEquals("Hallo {player}!", plain(service.component(de, ref)));
            Component button = Component.text("[").append(lang.translatable("shop.bought", 2, "Brot")).append(Component.text("]"));
            assertEquals("[Du hast {amount}x {item} gekauft.]", plain(service.render(de, button)));
            Component styled = lang.translatable("greeting").color(NamedTextColor.RED).decorate(TextDecoration.BOLD);
            Component rendered = service.render(player(Locale.US), styled);
            assertEquals("Hello {player}!", plain(rendered));
            assertEquals(NamedTextColor.RED, rendered.color());
        } finally {
            service.unregister(plugin);
        }
        assertTrue(service.bundle(plugin).isEmpty());
    }

    @Test
    void givenTranslatableWithPositionalArgs_whenRendered_thenArgumentsInserted() {
        Localization service = Localization.get();
        org.bukkit.plugin.Plugin plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getName()).thenReturn("PosPlugin");
        when(plugin.getDataFolder()).thenReturn(dir.resolve("PosPlugin").toFile());
        when(plugin.getResource("lang/en.yml")).thenAnswer(i -> new java.io.ByteArrayInputStream(
                "page: \"Page {0} of {1}\"\n".getBytes(StandardCharsets.UTF_8)));
        Lang lang = service.register(plugin);
        try {
            assertEquals("Page 2 of 5", plain(service.render(player(Locale.US), lang.translatable("page", 2, 5))));
        } finally {
            service.unregister(plugin);
        }
    }

    @Test
    void givenConcurrentLookupsAndReloads_whenRun_thenNoFailures() throws Exception {
        Lang lang = Lang.create("TestPlugin", Map.of("en", EN, "de", DE), dir.resolve("conc").toFile());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 300; i++) {
                        if (id == 0 && i % 50 == 0) lang.reload();
                        String text = lang.plain(i % 2 == 0 ? "de" : "en", "greeting", "player", "P" + i);
                        if (!text.endsWith("P" + i + "!")) throw new AssertionError(text);
                    }
                } catch (Throwable e) { errors.add(e); }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), () -> errors.peek().toString());
    }
}
