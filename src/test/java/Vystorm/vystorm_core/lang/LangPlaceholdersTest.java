package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Language texts resolve registered placeholders only after an opt-in, and never what a named placeholder inserts. */
class LangPlaceholdersTest {
    private static final String YAML = String.join("\n",
            "plain: \"Hello there\"",
            "named: \"<green>Hi {player}, online: {core:online}\"",
            "percent: \"Balance: %vystorm_xpvault_balance% of {percent} %\"",
            "unknown: \"{lang:demo:plain} {nobody:key} %vystorm_nobody_key% {amount}\"",
            "escaped: \"Write \\\\{core:online} for {core:online}\"",
            "rows:",
            "  - \"A {core:online}\"",
            "  - \"B %vystorm_core_online% {row}\"",
            "");

    private Supplier<PlaceholderInterface> before;
    private PlaceholderTesting testing;
    private Lang lang;
    private Player alex;

    @BeforeEach
    void setUp() {
        before = Lang.placeholderSource;
        testing = PlaceholderTesting.detached();
        testing.register(PlaceholderTesting.plugin("Core"), "core").global("online", "", () -> 3);
        testing.register(PlaceholderTesting.plugin("XPVault"), "xpvault")
                .value("balance", player -> player.getName() + "=120")
                .global("motd", "", () -> "<red>{core:online}</red>");
        Lang.placeholderSource = testing::placeholders;
        lang = Lang.create("demo", Map.of("en", YAML));
        alex = mock(Player.class);
        when(alex.getName()).thenReturn("Alex");
        when(alex.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Alex".getBytes()));
        when(alex.locale()).thenReturn(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        Lang.placeholderSource = before;
        Localization.get().placeholdersInTexts(false);
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    @Test
    void givenNoOptIn_whenTextsBuilt_thenShownAsWritten() {
        assertFalse(lang.placeholders());
        assertEquals("Hi Alex, online: {core:online}", lang.plain(alex, "named", "player", "Alex"));
        assertEquals("Balance: %vystorm_xpvault_balance% of 50 %", lang.plain(alex, "percent", "percent", 50));
        assertEquals(List.of("A {core:online}", "B %vystorm_core_online% x"), lang.lines(alex, "rows", "row", "x").stream().map(LangPlaceholdersTest::plain).toList());
        assertEquals("Write \\{core:online} for {core:online}", lang.plain(alex, "escaped"));
    }

    @Test
    void givenBundleOptIn_whenTextsBuiltForAViewer_thenRegisteredPlaceholdersResolved() {
        assertSame(lang, lang.placeholders(true));
        assertTrue(lang.placeholders());
        assertEquals("Hi Alex, online: 3", lang.plain(alex, "named", "player", "Alex"));
        assertEquals("Hi Alex, online: 3", plain(lang.component(alex, "named", "player", "Alex")));
        assertEquals("Balance: Alex=120 of 50 %", lang.plain(alex, "percent", "percent", 50));
        String legacy = lang.text(alex, "named", "player", "Alex");
        assertTrue(legacy.startsWith("§a"), legacy);
        assertEquals("Hi Alex, online: 3", legacy.replace("§a", ""));
        assertEquals(List.of("A 3", "B 3 x"), lang.lines(alex, "rows", "row", "x").stream().map(LangPlaceholdersTest::plain).toList());
        assertEquals("Write {core:online} for 3", lang.plain(alex, "escaped"));
        lang.placeholders(false);
        assertEquals("Hi Alex, online: {core:online}", lang.plain(alex, "named", "player", "Alex"));
    }

    @Test
    void givenCoreSetting_whenSwitchedOn_thenEveryBundleResolves() {
        Localization.get().placeholdersInTexts(true);
        assertTrue(lang.placeholders());
        assertEquals("Hi Alex, online: 3", lang.plain(alex, "named", "player", "Alex"));
    }

    @Test
    void givenNamedValueThatLooksLikeAPlaceholder_whenResolved_thenInsertedAsWritten() {
        lang.placeholders(true);
        assertEquals("Hi {core:online} %vystorm_core_online%, online: 3", lang.plain(alex, "named", "player", "{core:online} %vystorm_core_online%"));
    }

    @Test
    void givenRegisteredValueWithTagsAndPlaceholders_whenResolved_thenInsertedLiterally() {
        lang.placeholders(true);
        Lang motd = Lang.create("motd", Map.of("en", "line: \"<green>{xpvault:motd}!\"")).placeholders(true);
        Component line = motd.component(alex, "line");
        assertEquals("<red>{core:online}</red>!", plain(line));
    }

    @Test
    void givenTextWithOtherBraces_whenResolved_thenLanguageReferencesAndUnknownNamesStay() {
        lang.placeholders(true);
        assertEquals("{lang:demo:plain} {nobody:key} %vystorm_nobody_key% 5", lang.plain(alex, "unknown", "amount", 5));
    }

    @Test
    void givenLanguageCodeInsteadOfViewer_whenTextsBuilt_thenNothingIsResolved() {
        lang.placeholders(true);
        assertEquals("Hi Alex, online: {core:online}", lang.plain("en", "named", "player", "Alex"));
        assertEquals("A {core:online}", plain(lang.lines("en", "rows", "row", "x").get(0)));
    }

    @Test
    void givenConsole_whenTextsBuilt_thenGlobalValuesResolveAndPlayerValuesStay() {
        lang.placeholders(true);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertEquals("Hi console, online: 3", lang.plain(console, "named", "player", "console"));
        assertEquals("Balance: %vystorm_xpvault_balance% of 50 %", lang.plain(console, "percent", "percent", 50));
    }

    @Test
    void givenTextWithoutDelimiters_whenOptedIn_thenStillTheCachedComponent() {
        lang.placeholders(true);
        assertSame(lang.component(alex, "plain"), lang.component(alex, "plain"));
        assertEquals("Hello there", lang.plain(alex, "plain"));
    }

    @Test
    void givenReplacedManagerWithItsOwnSyntax_whenOptedIn_thenItIsAskedForEveryText() {
        lang.placeholders(true);
        PlaceholderInterface replaced = new PlaceholderInterface() {
            @Override public Prepared miniMessage(org.bukkit.OfflinePlayer viewer, Player other, String text) {
                return new Prepared(text.replace("there", "<own0>"),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("own0", viewer.getName()), 1);
            }
        };
        Lang.placeholderSource = () -> replaced;
        assertEquals("Hello Alex", lang.plain(alex, "plain"));
    }

    @Test
    void givenNoPlaceholderManager_whenOptedIn_thenTextsAreShownAsWritten() {
        lang.placeholders(true);
        Lang.placeholderSource = () -> null;
        assertEquals("Hi Alex, online: {core:online}", lang.plain(alex, "named", "player", "Alex"));
        Lang.placeholderSource = () -> { throw new IllegalStateException("no hub"); };
        assertEquals("Hi Alex, online: {core:online}", lang.plain(alex, "named", "player", "Alex"));
        assertEquals(List.of("A {core:online}", "B %vystorm_core_online% x"), lang.lines(alex, "rows", "row", "x").stream().map(LangPlaceholdersTest::plain).toList());
    }
}
