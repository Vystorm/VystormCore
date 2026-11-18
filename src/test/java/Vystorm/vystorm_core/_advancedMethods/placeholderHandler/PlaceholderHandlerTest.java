package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Registry, syntax and the rules of resolving, on a detached registry (no server). */
class PlaceholderHandlerTest {
    private PlaceholderTesting testing;
    private PlaceholderInterface placeholders;
    private Plugin plugin;
    private OfflinePlayer alex;

    @BeforeEach
    void setUp() {
        testing = PlaceholderTesting.detached();
        placeholders = testing.placeholders();
        plugin = PlaceholderTesting.plugin("XPVault");
        alex = PlaceholderTesting.viewer("Alex");
    }

    private static Player online(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getPlayer()).thenReturn(player);
        return player;
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    private static boolean anyPart(Component component, java.util.function.Predicate<Component> test) {
        if (test.test(component)) return true;
        for (Component child : component.children()) if (anyPart(child, test)) return true;
        return false;
    }

    // ------------------------------------------------------------------ syntax

    @Test
    void givenBothForms_whenResolved_thenSameValue() {
        placeholders.register(plugin, "xpvault").value("balance", player -> player.getName() + ":120");
        assertEquals("Balance: Alex:120", placeholders.resolve(alex, "Balance: %vystorm_xpvault_balance%"));
        assertEquals("Balance: Alex:120", placeholders.resolve(alex, "Balance: {xpvault:balance}"));
        assertEquals("Alex:120Alex:120", placeholders.resolve(alex, "%vystorm_xpvault_balance%{xpvault:balance}"));
    }

    @Test
    void givenUpperCaseNamespaceAndKey_whenResolved_thenMatchedWithoutCase() {
        placeholders.register(plugin, "XPVault").global("Accounts", "", () -> 7);
        assertEquals("7 7 7", placeholders.resolve(null, "%VYSTORM_XPVAULT_ACCOUNTS% {XpVault:Accounts} %vystorm_xpvault_accounts%"));
        assertEquals("xpvault", placeholders.namespaces().get(0).name());
        assertEquals("accounts", placeholders.namespaces().get(0).values().get(0).key());
    }

    @Test
    void givenTextWithoutDelimiters_whenResolved_thenSameInstance() {
        placeholders.register(plugin, "xpvault").global("accounts", "", () -> 7);
        String plainText = new String("Nothing to see here");
        assertSame(plainText, placeholders.resolve(alex, plainText));
        String untouched = new String("50% of {players} and {lang:core:key} and %unknown_thing%");
        assertSame(untouched, placeholders.resolve(alex, untouched));
        assertNull(placeholders.resolve(alex, null));
        assertEquals("", placeholders.resolve(alex, ""));
    }

    @Test
    void givenKeyWithUnderscores_whenResolved_thenLongestDeclaredKeyWins() {
        List<String> seen = new ArrayList<>();
        placeholders.register(plugin, "core")
                .resolver("world", "", "", context -> { seen.add("world" + context.arguments()); return "w"; })
                .resolver("world_time", "", "<world>", context -> { seen.add("world_time" + context.arguments()); return "t"; });
        assertEquals("t", placeholders.resolve(null, "%vystorm_core_world_time%"));
        assertEquals("t", placeholders.resolve(null, "%vystorm_core_world_time_world_nether%"));
        assertEquals("w", placeholders.resolve(null, "%vystorm_core_world_x%"));
        assertEquals("t", placeholders.resolve(null, "{core:world_time}"));
        assertEquals(List.of("world_time[]", "world_time[world, nether]", "world[x]", "world_time[]"), seen);
    }

    @Test
    void givenArguments_whenResolved_thenContextHasThemInBothForms() {
        placeholders.register(plugin, "boards").resolver("votes", "", "<board> <option>", context ->
                context.key() + "|" + context.argument(0) + "|" + context.argument(1) + "|" + context.argument(2)
                        + "|" + context.rest(1) + "|" + context.raw() + "|" + context.arguments().size());
        assertEquals("votes|Event|pvp|null|pvp|votes_Event_pvp|2", placeholders.resolve(null, "%vystorm_boards_votes_Event_pvp%"));
        assertEquals("votes|Event|pvp|null|pvp|votes:Event:pvp|2", placeholders.resolve(null, "{boards:votes:Event:pvp}"));
    }

    @Test
    void givenArgumentWithSeparators_whenRestIsUsed_thenItIsWrittenAsInTheText() {
        placeholders.register(plugin, "core")
                .resolver("time", "", "<pattern>", context -> context.rest(0))
                .resolver("level", "", "<n>", context -> context.intArgument(0, -1) + "/" + context.numberArgument(1, 0.5) + "/" + context.argument(2, "none"));
        assertEquals("HH:mm", placeholders.resolve(null, "{core:time:HH:mm}"));
        assertEquals("world_the_end", placeholders.resolve(null, "%vystorm_core_time_world_the_end%"));
        assertEquals("", placeholders.resolve(null, "{core:time}"));
        assertEquals("12/2.5/none", placeholders.resolve(null, "{core:level:12:2.5}"));
        assertEquals("-1/0.5/none", placeholders.resolve(null, "{core:level:twelve:NaN:}"));
    }

    @Test
    void givenUnknownPlaceholders_whenResolved_thenLeftAsWritten() {
        placeholders.register(plugin, "xpvault")
                .value("balance", player -> 5)
                .resolver("nothing", "", "", context -> null);
        String text = "%vystorm_xpvault_missing% %vystorm_nobody_balance% {nobody:balance} {xpvault:missing} "
                + "%vystorm_xpvault_nothing% {xpvault:nothing} %vystorm_xpvault% %vystorm_% {xpvault:} {xpvault}";
        assertSame(text, placeholders.resolve(alex, text));
        assertEquals("%vystorm_xpvault_balance% stays without a viewer", placeholders.resolve(null, "%vystorm_xpvault_balance% stays without a viewer"));
    }

    @Test
    void givenUnmatchedDelimiters_whenResolved_thenNothingBreaks() {
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        for (String text : List.of("%", "%%", "{", "}", "{}", "{:}", "50 % · {coverage} % of all", "%vystorm_core_online",
                "{core:online", "core:online}", "%vystorm_core_online\n%", "{core:on\nline}", "100%_done%", "%_x%", "{core:{", "\\", "\\%", "\\{"))
            assertSame(text, placeholders.resolve(null, text), text);
        assertEquals("%3", placeholders.resolve(null, "%%vystorm_core_online%"));
        assertEquals("3%", placeholders.resolve(null, "%vystorm_core_online%%"));
        assertEquals("{3}", placeholders.resolve(null, "{{core:online}}"));
        assertEquals("3 % 3", placeholders.resolve(null, "%vystorm_core_online% % {core:online}"));
    }

    @Test
    void givenNestedPlaceholders_whenResolved_thenOnlyTheInnerBraceIsAPlaceholder() {
        placeholders.register(plugin, "core")
                .global("online", "", () -> 3)
                .resolver("echo", "", "<text>", context -> "[" + context.rest(0) + "]");
        assertEquals("{core:echo:3}", placeholders.resolve(null, "{core:echo:{core:online}}"));
        // the percent form takes everything up to the next % as its arguments; the value is not read again
        assertEquals("[{core:online}]", placeholders.resolve(null, "%vystorm_core_echo_{core:online}%"));
    }

    @Test
    void givenBackslashBeforePlaceholder_whenResolved_thenShownAsWrittenWithoutTheBackslash() {
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        assertEquals("%vystorm_core_online% = 3", placeholders.resolve(null, "\\%vystorm_core_online% = %vystorm_core_online%"));
        assertEquals("{core:online} = 3", placeholders.resolve(null, "\\{core:online} = {core:online}"));
        assertEquals("%player_name%", placeholders.resolve(null, "\\%player_name%"));
        String notAPlaceholder = "C:\\{nobody:home} and a\\b";
        assertSame(notAPlaceholder, placeholders.resolve(null, notAPlaceholder));
    }

    @Test
    void givenVeryLongText_whenResolved_thenOnePassFindsThePlaceholders() {
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 200_000; i++) noise.append(i % 7 == 0 ? "% " : i % 11 == 0 ? "{x " : "word ");
        String without = noise.toString();
        String with = without + "%vystorm_core_online%" + without + "{core:online}";
        assertTimeout(Duration.ofSeconds(10), () -> {
            assertSame(without, placeholders.resolve(null, without));
            assertEquals(without + "3" + without + "3", placeholders.resolve(null, with));
        });
        String longToken = "%vystorm_core_" + "x".repeat(400) + "%";
        assertSame(longToken, placeholders.resolve(null, longToken));
    }

    // ------------------------------------------------------------------ safety

    @Test
    void givenValueThatLooksLikeAPlaceholder_whenResolved_thenItIsNotReadAgain() {
        placeholders.register(plugin, "chat")
                .global("last", "", () -> "%vystorm_chat_secret% {chat:secret} \\{chat:secret}")
                .global("secret", "", () -> "TOP");
        assertEquals("%vystorm_chat_secret% {chat:secret} \\{chat:secret} / TOP", placeholders.resolve(null, "%vystorm_chat_last% / {chat:secret}"));
        assertEquals("%vystorm_chat_secret% {chat:secret} \\{chat:secret}", plain(placeholders.component(null, "{chat:last}")));
    }

    @Test
    void givenValueWithMiniMessageTags_whenComponentBuilt_thenInsertedLiterally() {
        placeholders.register(plugin, "chat")
                .global("name", "", () -> "<red>Evil</red><click:run_command:'/op me'>x")
                .add(PlaceholderValue.global("rank", () -> "<red>Admin</red>").formatted());
        Component literal = placeholders.component(null, "<green>Hi {chat:name}!");
        assertEquals("Hi <red>Evil</red><click:run_command:'/op me'>x!", plain(literal));
        assertTrue(anyPart(literal, part -> NamedTextColor.GREEN.equals(part.color())));
        assertFalse(anyPart(literal, part -> part.clickEvent() != null || NamedTextColor.RED.equals(part.color())));

        Component formatted = placeholders.component(null, "Rank: %vystorm_chat_rank%.");
        assertEquals("Rank: Admin.", plain(formatted));
        assertTrue(anyPart(formatted, part -> NamedTextColor.RED.equals(part.color())));
        assertEquals("Rank: §cAdmin.", placeholders.resolve(null, "Rank: %vystorm_chat_rank%."));
    }

    @Test
    void givenTextThatUsesThePrivateTagName_whenPrepared_thenAnotherNameIsChosen() {
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        PlaceholderInterface.Prepared prepared = placeholders.miniMessage(null, null, "<vyp0> {core:online} \\{core:online}");
        assertEquals("<vyp0> <vypx0> {core:online}", prepared.text());
        assertEquals(1, prepared.replaced());
        PlaceholderInterface.Prepared nothing = placeholders.miniMessage(null, null, "<red>plain");
        assertEquals("<red>plain", nothing.text());
        assertEquals(0, nothing.replaced());
        assertEquals("", placeholders.miniMessage(null, null, null).text());
    }

    @Test
    void givenLegacyColoursInValue_whenComponentBuilt_thenShownAsColours() {
        placeholders.register(plugin, "chat").global("prefix", "", () -> "§6VIP");
        Component component = placeholders.component(null, "[{chat:prefix}]");
        assertEquals("[VIP]", plain(component));
    }

    @Test
    void givenResolverThatThrows_whenResolved_thenPlaceholderStaysAndOthersAnswer() {
        placeholders.register(plugin, "xpvault")
                .global("broken", "", () -> { throw new IllegalStateException("database gone"); })
                .global("fine", "", () -> "ok");
        assertEquals("%vystorm_xpvault_broken% ok", placeholders.resolve(null, "%vystorm_xpvault_broken% {xpvault:fine}"));
    }

    @Test
    void givenResolverThatResolvesItsOwnPlaceholder_whenResolved_thenRecursionStops() {
        placeholders.register(plugin, "loop").global("again", "", () -> "<" + placeholders.resolve(null, "{loop:again}") + ">");
        String result = placeholders.resolve(null, "{loop:again}");
        assertTrue(result.endsWith("{loop:again}>>>>>>>>"), result);
        assertEquals(8, result.chars().filter(c -> c == '<').count());
    }

    // ------------------------------------------------------------------ registry

    @Test
    void givenNamespace_whenRegisteredAgainByItsOwner_thenTheSameNamespaceWithItsValues() {
        PlaceholderNamespace first = placeholders.register(plugin, "xpvault").value("balance", player -> 1);
        PlaceholderNamespace second = placeholders.value(plugin, "xpvault", "level", player -> 2);
        assertSame(first, second);
        assertEquals(List.of("balance", "level"), first.values().stream().map(PlaceholderValue::key).toList());
        first.value("balance", player -> 9);
        assertEquals("9 2", placeholders.resolve(alex, "{xpvault:balance} {xpvault:level}"));
        assertTrue(first.remove("LEVEL"));
        assertFalse(first.remove("level"));
        assertEquals("{xpvault:level}", placeholders.resolve(alex, "{xpvault:level}"));
        assertTrue(first.value("balance").isPresent());
        assertSame(first, placeholders.namespace("XPVAULT").orElseThrow());
        assertSame(plugin, first.owner());
    }

    @Test
    void givenNamespaceOfAnotherEnabledPlugin_whenRegistered_thenRejected() {
        placeholders.register(plugin, "xpvault");
        Plugin other = PlaceholderTesting.plugin("Other");
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> placeholders.register(other, "xpvault"));
        assertTrue(rejected.getMessage().contains("XPVault"), rejected.getMessage());
        assertFalse(placeholders.unregister(other, "xpvault"));
        assertTrue(placeholders.unregister(plugin, "xpvault"));
        assertSame(other, placeholders.register(other, "xpvault").owner());
    }

    @Test
    void givenNamespaceOfADisabledPlugin_whenAnotherRegistersIt_thenItIsTakenOverAndNotAskedBefore() {
        Plugin old = mock(Plugin.class);
        when(old.getName()).thenReturn("Old");
        when(old.isEnabled()).thenReturn(true);
        PlaceholderNamespace stale = placeholders.register(old, "xpvault").global("accounts", "", () -> 1);
        assertEquals("1", placeholders.resolve(null, "{xpvault:accounts}"));
        when(old.isEnabled()).thenReturn(false);
        assertEquals("{xpvault:accounts}", placeholders.resolve(null, "{xpvault:accounts}"));
        placeholders.register(plugin, "xpvault").global("accounts", "", () -> 2);
        assertFalse(stale.registered());
        assertEquals("2", placeholders.resolve(null, "{xpvault:accounts}"));
    }

    @Test
    void givenInvalidOrReservedNamespace_whenRegistered_thenRejected() {
        for (String name : List.of("", "1abc", "my_plugin", "my-plugin", "a b", "x".repeat(33), "näme"))
            assertThrows(IllegalArgumentException.class, () -> placeholders.register(plugin, name), name);
        for (String name : List.of("lang", "LANG", "data", "rel", "vystorm", "votes", "vote", "guestbook", "countdown"))
            assertThrows(IllegalArgumentException.class, () -> placeholders.register(plugin, name), name);
        assertThrows(NullPointerException.class, () -> placeholders.register(null, "abc"));
        assertThrows(NullPointerException.class, () -> placeholders.register(plugin, null));
        for (String key : List.of("", "_a", "a_", "a b", "a:b", "a%b", "x".repeat(65)))
            assertThrows(IllegalArgumentException.class, () -> PlaceholderValue.global(key, () -> 1), key);
        assertEquals("a_b.c-d", PlaceholderValue.global("A_b.c-D", () -> 1).key());
    }

    @Test
    void givenPluginDisabled_whenCoreReleasesIt_thenItsNamespacesAreGone() {
        Plugin other = PlaceholderTesting.plugin("Boards");
        PlaceholderNamespace mine = placeholders.register(plugin, "xpvault").global("accounts", "", () -> 1);
        placeholders.register(plugin, "xpbank").global("vaults", "", () -> 2);
        placeholders.register(other, "boards").global("count", "", () -> 3);
        assertEquals(2, testing.disable(plugin));
        assertFalse(mine.registered());
        assertEquals("{xpvault:accounts} %vystorm_xpbank_vaults% 3", placeholders.resolve(null, "{xpvault:accounts} %vystorm_xpbank_vaults% {boards:count}"));
        assertEquals(List.of("boards"), placeholders.namespaces().stream().map(PlaceholderNamespace::name).toList());
        assertEquals(0, testing.disable(plugin));
        assertEquals(0, placeholders.unregisterAll(null));
    }

    @Test
    void givenNamespaceWideResolver_whenUndeclaredKeyAsked_thenItAnswersAndDeclaredValuesWin() {
        PlaceholderNamespace namespace = placeholders.register(plugin, "legacy", context ->
                context.key().equals("unknown") ? null : context.key() + context.arguments() + "/" + context.raw());
        namespace.global("fixed", "", () -> "declared");
        assertTrue(namespace.hasFallback());
        assertEquals("top[3, kills]/top_3_kills", placeholders.resolve(null, "%vystorm_legacy_top_3_kills%"));
        assertEquals("top[3, kills]/Top:3:kills", placeholders.resolve(null, "{legacy:Top:3:kills}"));
        assertEquals("declared", placeholders.resolve(null, "{legacy:fixed}"));
        assertEquals("{legacy:unknown}", placeholders.resolve(null, "{legacy:unknown}"));
        namespace.fallback(null);
        assertFalse(namespace.hasFallback());
        assertEquals("{legacy:top}", placeholders.resolve(null, "{legacy:top}"));
    }

    @Test
    void givenDirectLookups_whenAsked_thenValueOrNull() {
        placeholders.register(plugin, "boards")
                .resolver("votes", "", "<board>", context -> context.viewer() == null ? null : context.argument(0) + "=" + context.argument(1))
                .global("count", "", () -> 4);
        assertEquals("event=pvp", placeholders.value(alex, "boards", "votes", "event", "pvp"));
        assertEquals("4", placeholders.value(null, "Boards", "COUNT"));
        assertNull(placeholders.value(null, "boards", "votes", "event"));
        assertNull(placeholders.value(alex, "boards", "missing"));
        assertNull(placeholders.value(alex, "nobody", "count"));
        assertEquals("event=pvp", placeholders.request(alex, null, "boards_votes_event_pvp"));
        assertEquals("4", placeholders.request(null, null, "BOARDS_count"));
        assertNull(placeholders.request(null, null, "boards"));
        assertNull(placeholders.request(null, null, "boards_"));
        assertNull(placeholders.request(null, null, "nobody_count"));
        assertNull(placeholders.request(null, null, null));
    }

    // ------------------------------------------------------------------ viewers

    @Test
    void givenViewerKinds_whenResolved_thenValuesThatNeedMoreStayAsWritten() {
        Player steve = online("Steve");
        placeholders.register(plugin, "who")
                .value("any", "", player -> player.getName())
                .player("online", "", player -> player.getName() + "!")
                .relational("pair", "", (one, two) -> one.getName() + ">" + two.getName());
        assertEquals("Alex {who:online} {who:pair}", placeholders.resolve(alex, "{who:any} {who:online} {who:pair}"));
        assertEquals("Steve Steve! {who:pair}", placeholders.resolve(steve, "{who:any} {who:online} {who:pair}"));
        assertEquals("{who:any}", placeholders.resolveFor(mock(org.bukkit.command.ConsoleCommandSender.class), "{who:any}"));
        assertEquals("Steve", placeholders.resolveFor(steve, "{who:any}"));
    }

    @Test
    void givenSecondPlayer_whenResolved_thenRelationalValuesAnswerInEveryForm() {
        Player steve = online("Steve"), herobrine = online("Herobrine");
        placeholders.register(plugin, "party").relational("same", "", (one, two) -> one.getName() + ">" + two.getName());
        assertEquals("Steve>Herobrine Steve>Herobrine Steve>Herobrine",
                placeholders.resolve(steve, herobrine, "{party:same} %vystorm_party_same% %rel_vystorm_party_same%"));
        assertEquals("Steve>Herobrine", placeholders.request(steve, herobrine, "party_same"));
        assertEquals("%rel_vystorm_party_same% %rel_vystorm_%", placeholders.resolve(steve, "%rel_vystorm_party_same% %rel_vystorm_%"));
    }

    // ------------------------------------------------------------------ threads and cache

    @Test
    void givenAnotherThread_whenResolved_thenOnlyAsyncSafeValuesAnswer() {
        AtomicInteger mainOnly = new AtomicInteger();
        List<Boolean> threads = new ArrayList<>();
        PlaceholderNamespace namespace = placeholders.register(plugin, "xpvault")
                .add(PlaceholderValue.global("sync", () -> { mainOnly.incrementAndGet(); return "S"; }))
                .add(PlaceholderValue.of("safe", context -> { threads.add(context.onServerThread()); return "A"; }).asyncSafe());
        assertEquals("S A", testing.resolve(null, "{xpvault:sync} {xpvault:safe}"));
        assertEquals("{xpvault:sync} A %vystorm_xpvault_sync%", testing.resolveOffThread(null, "{xpvault:sync} {xpvault:safe} %vystorm_xpvault_sync%"));
        assertEquals(1, mainOnly.get());
        assertEquals(List.of(true, false), threads);

        namespace.fallback(context -> "F");
        assertEquals("{xpvault:other}", testing.resolveOffThread(null, "{xpvault:other}"));
        namespace.fallback(context -> "F", true);
        assertEquals("F", testing.resolveOffThread(null, "{xpvault:other}"));
        assertTrue(namespace.value("safe").orElseThrow().isAsyncSafe());
        assertFalse(namespace.value("sync").orElseThrow().isAsyncSafe());
    }

    @Test
    void givenCachedValue_whenAskedWithinItsTicks_thenComputedOncePerViewerAndArguments() {
        AtomicInteger calls = new AtomicInteger();
        placeholders.register(plugin, "top")
                .add(PlaceholderValue.of("rank", context -> context.argument(0, "1") + "#" + calls.incrementAndGet()).cacheTicks(20))
                .add(PlaceholderValue.global("live", calls::incrementAndGet));
        assertEquals("1#1", testing.resolve(alex, "{top:rank}"));
        assertEquals("1#1", testing.resolve(alex, "{top:rank}"));
        assertEquals("2#2", testing.resolve(alex, "{top:rank:2}"));
        assertEquals("1#3", testing.resolve(PlaceholderTesting.viewer("Steve"), "{top:rank}"));
        testing.advanceTicks(19);
        assertEquals("1#1", testing.resolve(alex, "{top:rank}"));
        testing.advanceTicks(1);
        assertEquals("1#4", testing.resolve(alex, "{top:rank}"));
        assertEquals("5 6", testing.resolve(alex, "{top:live} {top:live}"));
    }

    // ------------------------------------------------------------------ bridges

    private static final class FakeBridge implements PlaceholderBridge {
        final List<String> asked = new ArrayList<>();
        boolean available = true, async, providing, withdrawn;
        int provided;
        Source source;

        @Override public String name() { return "FakePlaceholders"; }
        @Override public boolean available() { return available; }
        @Override public boolean provide(Source source) { this.source = source; provided++; providing = available; return providing; }
        @Override public boolean providing() { return providing; }
        @Override public void withdraw() { withdrawn = true; providing = false; }
        @Override public boolean asyncSafe() { return async; }

        @Override
        public String consume(OfflinePlayer viewer, Player other, String placeholder) {
            asked.add(placeholder);
            return switch (placeholder) {
                case "%player_name%" -> viewer == null ? "" : viewer.getName();
                case "%server_evil%" -> "%vystorm_core_online% <red>{core:online}";
                case "%server_boom%" -> throw new IllegalStateException("expansion broke");
                default -> placeholder;
            };
        }
    }

    @Test
    void givenBridge_whenTextHasForeignPlaceholders_thenEachIsAskedOnceAndOursNever() {
        FakeBridge bridge = new FakeBridge();
        testing.bridge(bridge);
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        assertEquals("Alex 3 %unknown_thing% %vystorm_core_missing% 50 % x",
                placeholders.resolve(alex, "%player_name% %vystorm_core_online% %unknown_thing% %vystorm_core_missing% 50 % x"));
        assertEquals(List.of("%player_name%", "%unknown_thing%"), bridge.asked);
        assertEquals(List.of(bridge), placeholders.bridges());
    }

    @Test
    void givenForeignValueWithPlaceholdersAndTags_whenResolved_thenNotReadAgainAndNotParsed() {
        testing.bridge(new FakeBridge());
        placeholders.register(plugin, "core").global("online", "", () -> 3);
        assertEquals("%vystorm_core_online% <red>{core:online}", placeholders.resolve(alex, "%server_evil%"));
        Component component = placeholders.component(alex, "<green>%server_evil%");
        assertEquals("%vystorm_core_online% <red>{core:online}", plain(component));
    }

    @Test
    void givenBridgeThatFailsOrIsUnavailable_whenResolved_thenPlaceholderStays() {
        FakeBridge bridge = new FakeBridge();
        testing.bridge(bridge);
        assertEquals("%server_boom% Alex", placeholders.resolve(alex, "%server_boom% %player_name%"));
        bridge.available = false;
        assertEquals("%player_name%", placeholders.resolve(alex, "%player_name%"));
    }

    @Test
    void givenAnotherThread_whenForeignPlaceholderResolved_thenOnlyAnAsyncSafeBridgeIsAsked() {
        FakeBridge bridge = new FakeBridge();
        testing.bridge(bridge);
        assertEquals("%player_name%", testing.resolveOffThread(alex, "%player_name%"));
        assertEquals(List.of(), bridge.asked);
        bridge.async = true;
        assertEquals("Alex", testing.resolveOffThread(alex, "%player_name%"));
    }

    @Test
    void givenBridge_whenAddedRefreshedAndRemoved_thenProvidedAndWithdrawn() {
        FakeBridge bridge = new FakeBridge();
        Plugin owner = PlaceholderTesting.plugin("BridgePlugin");
        placeholders.register(plugin, "xpvault").value("balance", player -> player.getName() + "=5").global("accounts", "", () -> 2);
        placeholders.bridge(owner, bridge);
        assertEquals(1, bridge.provided);
        assertTrue(bridge.providing());
        assertEquals("Alex=5", bridge.source.request(alex, null, "xpvault_balance"));
        assertNull(bridge.source.request(alex, null, "xpvault_missing"));
        assertEquals(List.of("xpvault_balance", "xpvault_accounts"), bridge.source.known());
        placeholders.refreshBridges();
        assertEquals(2, bridge.provided);

        FakeBridge replacement = new FakeBridge();
        placeholders.bridge(owner, replacement);
        assertTrue(bridge.withdrawn);
        assertEquals(List.of(replacement), placeholders.bridges());

        assertEquals(1, placeholders.unregisterAll(owner));
        assertTrue(replacement.withdrawn);
        assertEquals(List.of(), placeholders.bridges());
        assertFalse(placeholders.removeBridge(replacement));

        FakeBridge third = new FakeBridge();
        placeholders.bridge(owner, third);
        assertTrue(placeholders.removeBridge(third));
        assertTrue(third.withdrawn);
    }

    @Test
    void givenReplacedManager_whenBridgeAsksItsSource_thenTheRoutedManagerAnswers() {
        FakeBridge bridge = new FakeBridge();
        testing.bridge(bridge);
        PlaceholderInterface mine = new PlaceholderInterface() {
            @Override public String request(OfflinePlayer viewer, Player other, String params) { return "mine:" + params; }
            @Override public List<PlaceholderNamespace> namespaces() { return List.of(); }
        };
        ((PlaceholderHandler) placeholders).route(() -> mine);
        assertEquals("mine:a_b", bridge.source.request(null, null, "a_b"));
        assertEquals(List.of(), bridge.source.known());
        ((PlaceholderHandler) placeholders).route(null);
        assertNull(bridge.source.request(null, null, "a_b"));
    }

    @Test
    void givenDetachedRegistries_whenUsed_thenTheyShareNothing() {
        PlaceholderTesting other = PlaceholderTesting.detached();
        placeholders.register(plugin, "xpvault").global("accounts", "", () -> 1);
        assertEquals("{xpvault:accounts}", other.resolve(null, "{xpvault:accounts}"));
        assertNotSame(placeholders, other.placeholders());
        assertNotSame(PlaceholderHandler.shared(), placeholders);
        assertTrue(PlaceholderHandler.mayContain("a%b") && PlaceholderHandler.mayContain("a{b") && !PlaceholderHandler.mayContain("ab") && !PlaceholderHandler.mayContain(null));
    }
}
