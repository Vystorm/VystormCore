package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo.Format;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource.Row;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rankings declared in a placeholder namespace: names, values, order, the thread rule, failures and owners. */
class RankingPlaceholdersTest {
    private PlaceholderTesting testing;
    private PlaceholderInterface placeholders;
    private Plugin paddock;
    private final OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
    private final OfflinePlayer bea = PlaceholderTesting.viewer("Bea");
    private final OfflinePlayer cid = PlaceholderTesting.viewer("Cid");
    private final OfflinePlayer nobody = PlaceholderTesting.viewer("Nobody");

    private static UUID id(OfflinePlayer player) { return player.getUniqueId(); }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** An in-memory store as a plugin would have one; deliberately NOT sorted. */
    private List<Row> wins() {
        return List.of(new Row(id(bea), "Bea", 7), new Row(id(alex), "Alex", 1234), new Row(id(cid), "Cid", 7));
    }

    @BeforeEach
    void setUp() {
        testing = PlaceholderTesting.detached();
        placeholders = testing.placeholders();
        paddock = PlaceholderTesting.plugin("VystormPaddock");
        placeholders.register(paddock, "paddock")
                .ranking("race-wins", RankingInfo.of("Race wins", "Races won"), n -> wins())
                .ranking("sled-best", RankingInfo.of("Best sled run", null).ascending().format(Format.DURATION_MILLIS),
                        n -> List.of(new Row(id(alex), "Alex", 65_007), new Row(id(bea), "Bea", 61_250)));
    }

    @Test
    void givenDeclaredRankings_whenListed_thenIdsInfoAndOwner() {
        List<PlaceholderRanking> all = testing.rankings();
        assertEquals(List.of("paddock:race-wins", "paddock:sled-best"), all.stream().map(PlaceholderRanking::id).toList());
        assertEquals("Race wins", all.get(0).info().label());
        assertSame(paddock, all.get(0).owner());
        assertEquals(all.get(1), placeholders.ranking("PADDOCK:Sled-Best").orElseThrow());
        assertTrue(placeholders.ranking("paddock:nothing").isEmpty());
        assertTrue(placeholders.ranking("paddock").isEmpty());
        assertTrue(placeholders.ranking(null).isEmpty());
        assertEquals(all, placeholders.namespace("paddock").orElseThrow().rankings());
    }

    @Test
    void givenDashedKey_whenTopPlaceholdersResolved_thenNameValueRawAndUuidOfThatRow() {
        assertEquals("Alex", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"));
        assertEquals("1,234", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_value%"));
        assertEquals("1234", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_raw%"));
        assertEquals(id(alex).toString(), testing.resolve(null, "%vystorm_paddock_top_race-wins_1_uuid%"));
        assertEquals("Bea Cid", testing.resolve(null, "%vystorm_paddock_top_race-wins_2_name% %vystorm_paddock_top_race-wins_3_name%"),
                "ties keep the order the source gave (stable sort)");
        assertEquals("Bea 1:01.250", testing.resolve(null, "%vystorm_paddock_top_sled-best_1_name% %vystorm_paddock_top_sled-best_1_value%"),
                "ascending: the fastest time first");
        assertEquals("Alex", testing.resolve(null, "{paddock:top_race-wins:1:name}"), "brace form: key, then arguments");
        assertEquals("Alex", testing.resolve(null, "%VYSTORM_PADDOCK_TOP_RACE-WINS_1_NAME%"), "names fold case");
    }

    @Test
    void givenRowThatDoesNotExist_whenResolved_thenEmpty_andInvalidNamesStayAsWritten() {
        assertEquals("[]", testing.resolve(null, "[%vystorm_paddock_top_race-wins_4_name%]"));
        assertEquals("[]", testing.resolve(null, "[%vystorm_paddock_top_race-wins_100_value%]"));
        for (String untouched : List.of("%vystorm_paddock_top_race-wins_0_name%", "%vystorm_paddock_top_race-wins_101_name%",
                "%vystorm_paddock_top_race-wins_1_colour%", "%vystorm_paddock_top_race-wins_1%", "%vystorm_paddock_top_race-wins%",
                "%vystorm_paddock_top_race-wins_x_name%", "%vystorm_paddock_top_race_wins_1_name%", "%vystorm_paddock_top_unknown_1_name%",
                "%vystorm_paddock_top_race-wins_1_name_extra%", "%vystorm_paddock_rank_race-wins_extra%"))
            assertEquals(untouched, testing.resolve(alex, untouched), untouched);
    }

    @Test
    void givenViewer_whenRankAndValueResolved_thenHisOrDashWhenUnranked_andConsoleStaysAsWritten() {
        assertEquals("1 1,234", testing.resolve(alex, "%vystorm_paddock_rank_race-wins% %vystorm_paddock_value_race-wins%"));
        assertEquals("3 7", testing.resolve(cid, "%vystorm_paddock_rank_race-wins% %vystorm_paddock_value_race-wins%"));
        assertEquals("- -", testing.resolve(nobody, "%vystorm_paddock_rank_race-wins% %vystorm_paddock_value_race-wins%"));
        assertEquals("2 1:05.007", testing.resolve(alex, "{paddock:rank_sled-best} {paddock:value_sled-best}"));
        assertEquals("%vystorm_paddock_rank_race-wins%", testing.resolve(null, "%vystorm_paddock_rank_race-wins%"));
    }

    @Test
    void givenViewerLanguage_whenValueFormatted_thenSeparatorsOfThatLanguage_andRawUnchanged() {
        testing.language("de");
        assertEquals("1.234 1234", testing.resolve(alex, "%vystorm_paddock_value_race-wins% %vystorm_paddock_top_race-wins_1_raw%"));
    }

    @Test
    void givenSourceWithOwnLookups_whenViewerOutsideTheTop_thenSourceAnswers() {
        placeholders.register(paddock, "paddock").ranking("animals", RankingInfo.of("Herd size", null), new RankingSource() {
            @Override public List<Row> top(int n) { return List.of(new Row(id(alex), "Alex", 40)); }
            @Override public OptionalInt rank(UUID player) { return player.equals(id(nobody)) ? OptionalInt.of(250) : OptionalInt.empty(); }
            @Override public OptionalDouble value(UUID player) { return player.equals(id(nobody)) ? OptionalDouble.of(2) : OptionalDouble.empty(); }
        });
        assertEquals("250 2", testing.resolve(nobody, "%vystorm_paddock_rank_animals% %vystorm_paddock_value_animals%"));
        assertEquals("1 40", testing.resolve(alex, "%vystorm_paddock_rank_animals% %vystorm_paddock_value_animals%"));
        assertEquals(OptionalInt.of(250), placeholders.rank("paddock:animals", id(nobody)));
        assertEquals(OptionalDouble.of(40), placeholders.rankingValue("paddock:animals", id(alex)));
        assertEquals(OptionalInt.empty(), placeholders.rank("paddock:animals", id(bea)));
    }

    @Test
    void givenTop_whenAsked_thenSortedCleanedAndCappedAtHundred() {
        AtomicInteger asked = new AtomicInteger();
        placeholders.register(paddock, "paddock").ranking("many", RankingInfo.of("Many", null), n -> {
            asked.set(n);
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < 150; i++) rows.add(new Row(id("p" + i), "p" + i, i));
            rows.add(null);
            rows.add(new Row(id("nan"), "nan", Double.NaN));
            return rows;
        });
        List<Row> top = placeholders.top("paddock:many", 500);
        assertEquals(100, asked.get(), "n is capped before the source is asked");
        assertEquals(100, top.size());
        assertEquals("p149", top.get(0).name());
        assertEquals(List.of("Alex", "Bea", "Cid"), placeholders.top("paddock:race-wins", 3).stream().map(Row::name).toList());
        assertEquals(List.of("Alex"), placeholders.top("Paddock:Race-Wins", 1).stream().map(Row::name).toList());
        assertEquals(List.of(), placeholders.top("paddock:race-wins", 0));
        assertEquals(List.of(), placeholders.top("paddock:unknown", 3));
        assertEquals(List.of(), placeholders.top("nothing", 3));
        assertThrows(UnsupportedOperationException.class, () -> top.add(null));
    }

    @Test
    void givenSourceThatThrowsOrReturnsNull_whenAsked_thenEmptyAndOneLogLinePerRanking() {
        placeholders.register(paddock, "paddock")
                .ranking("broken", RankingInfo.of("Broken", null), n -> { throw new IllegalStateException("store closed"); })
                .ranking("nulls", RankingInfo.of("Nulls", null), n -> null);
        assertEquals(List.of(), placeholders.top("paddock:broken", 10));
        assertEquals("[]", testing.resolve(alex, "[%vystorm_paddock_top_broken_1_name%]"));
        assertEquals("-", testing.resolve(alex, "%vystorm_paddock_rank_broken%"));
        assertEquals(List.of(), placeholders.top("paddock:broken", 10));
        assertEquals(1, testing.warnings().size(), testing.warnings().toString());
        assertTrue(testing.warnings().get(0).contains("paddock:broken"), testing.warnings().get(0));
        assertTrue(testing.warnings().get(0).contains("VystormPaddock"));
        assertEquals(List.of(), placeholders.top("paddock:nulls", 10));
        assertEquals(1, testing.warnings().size(), "null is empty, not a failure");
        assertEquals("Alex", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"), "other rankings keep working");
    }

    @Test
    void givenOneTick_whenManyPlaceholdersResolved_thenSourceAskedOnce() {
        AtomicInteger calls = new AtomicInteger();
        placeholders.register(paddock, "paddock").ranking("counted", RankingInfo.of("Counted", null), n -> {
            calls.incrementAndGet();
            return wins();
        });
        testing.resolve(alex, "%vystorm_paddock_top_counted_1_name% %vystorm_paddock_top_counted_2_name% %vystorm_paddock_rank_counted%");
        assertEquals(1, calls.get());
        testing.advanceTicks(1);
        testing.resolve(alex, "%vystorm_paddock_top_counted_1_name%");
        assertEquals(2, calls.get());
    }

    @Test
    void givenOtherThread_whenTopCalled_thenFailsFast_andTopAsyncRunsOnServerThread() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<CompletableFuture<List<Row>>> future = new AtomicReference<>();
        AtomicReference<String> offThreadText = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { placeholders.top("paddock:race-wins", 3); }
            catch (Throwable failure) { thrown.set(failure); }
            future.set(placeholders.topAsync("paddock:race-wins", 2));
        });
        worker.start();
        worker.join();
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertTrue(thrown.get().getMessage().contains("topAsync"));
        assertFalse(future.get().isDone(), "waits for the server thread");
        assertEquals(1, testing.runServerTasks());
        assertEquals(List.of("Alex", "Bea"), future.get().get(1, TimeUnit.SECONDS).stream().map(Row::name).toList());
        assertTrue(placeholders.topAsync("paddock:race-wins", 1).isDone(), "on the server thread it is complete at once");
        offThreadText.set(testing.resolveOffThread(alex, "%vystorm_paddock_top_race-wins_1_name%"));
        assertEquals("%vystorm_paddock_top_race-wins_1_name%", offThreadText.get(), "sources are never asked off the server thread");
    }

    @Test
    void givenTopAsyncWhenServerRunsNoTasks_thenFutureFails() {
        PlaceholderTesting other = PlaceholderTesting.detached();
        ((PlaceholderHandler) other.placeholders()).serverExecutor(task -> { throw new IllegalStateException("disabled"); });
        AtomicReference<CompletableFuture<List<Row>>> future = new AtomicReference<>();
        Thread worker = new Thread(() -> future.set(other.placeholders().topAsync("a:b", 1)));
        worker.start();
        assertTimeoutJoin(worker);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get().get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    private static void assertTimeoutJoin(Thread worker) {
        try { worker.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        assertFalse(worker.isAlive());
    }

    @Test
    void givenPluginsOwnPlaceholders_whenSameNames_thenThePluginWins() {
        Plugin owner = PlaceholderTesting.plugin("XPVaultEconomy");
        PlaceholderNamespace xpvault = placeholders.register(owner, "xpvault");
        xpvault.add(PlaceholderValue.of("top", context -> "own top " + context.rest(0)));       // shorter key with arguments
        xpvault.add(PlaceholderValue.viewer("rank_balance", player -> "own rank"));            // exactly the generated name
        xpvault.ranking("balance", RankingInfo.of("Richest", null), n -> List.of(new Row(id(alex), "Alex", 5)));
        assertEquals("own top balance_1_name", testing.resolve(alex, "%vystorm_xpvault_top_balance_1_name%"));
        assertEquals("own rank", testing.resolve(alex, "%vystorm_xpvault_rank_balance%"));
        assertEquals("5", testing.resolve(alex, "%vystorm_xpvault_value_balance%"), "names the plugin does not use are Core's");
        assertEquals("own rank", xpvault.value("rank_balance").orElseThrow().resolver().resolve(
                new PlaceholderContext(alex, null, "xpvault", "rank_balance", List.of(), "rank_balance", "_", true)));

        xpvault.add(PlaceholderValue.viewer("value_balance", player -> "declared later"));
        assertEquals("declared later", testing.resolve(alex, "%vystorm_xpvault_value_balance%"), "declaring later replaces Core's");
        assertTrue(xpvault.removeRanking("balance"));
        assertEquals("declared later", testing.resolve(alex, "%vystorm_xpvault_value_balance%"), "removing keeps the plugin's own");
        assertEquals("own rank", testing.resolve(alex, "%vystorm_xpvault_rank_balance%"));
        assertTrue(xpvault.rankings().isEmpty());
    }

    @Test
    void givenPluginFallback_whenItAnswers_thenItWinsOverTheRanking() {
        Plugin owner = PlaceholderTesting.plugin("Dyn");
        PlaceholderNamespace dyn = placeholders.register(owner, "dyn", context -> context.key().equals("top") ? "fallback" : null);
        dyn.ranking("wins", RankingInfo.of("Wins", null), n -> List.of(new Row(id(alex), "Alex", 3)));
        assertEquals("fallback", testing.resolve(alex, "%vystorm_dyn_top_wins_1_name%"));
        assertEquals("1", testing.resolve(alex, "%vystorm_dyn_rank_wins%"), "the fallback gives nothing for rank: Core answers");
        assertEquals("Alex", testing.resolve(alex, "{dyn:top_wins:1:name}"), "brace form: the fallback sees key top_wins and answers null");
    }

    @Test
    void givenSameKeyAgain_whenDeclared_thenReplaced_andRemoveDropsThePlaceholders() {
        PlaceholderNamespace namespace = placeholders.register(paddock, "paddock");
        namespace.ranking("Race-Wins", RankingInfo.of("Wins again", null), n -> List.of(new Row(id(cid), "Cid", 1)));
        assertEquals(2, testing.rankings().size());
        assertEquals("Wins again", placeholders.ranking("paddock:race-wins").orElseThrow().info().label());
        assertEquals("Cid", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"));
        assertTrue(namespace.removeRanking("race-wins"));
        assertFalse(namespace.removeRanking("race-wins"));
        assertEquals("%vystorm_paddock_top_race-wins_1_name%", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"));
        assertTrue(namespace.value("top_race-wins").isEmpty());
        assertEquals(List.of("paddock:sled-best"), testing.rankings().stream().map(PlaceholderRanking::id).toList());
    }

    @Test
    void givenGeneratedValues_whenNamespaceListed_thenDescribedWithArguments() {
        PlaceholderValue top = placeholders.namespace("paddock").orElseThrow().value("top_race-wins").orElseThrow();
        assertEquals("Race wins", top.description());
        assertEquals("<n> <name|value|raw|uuid>", top.arguments());
        assertFalse(top.isAsyncSafe());
        assertTrue(placeholders.namespace("paddock").orElseThrow().value("rank_race-wins").isPresent());
        assertTrue(placeholders.namespace("paddock").orElseThrow().value("value_sled-best").isPresent());
    }

    @Test
    void givenInvalidKeys_whenDeclared_thenRejected() {
        PlaceholderNamespace namespace = placeholders.register(paddock, "paddock");
        for (String bad : List.of("race_wins", "-wins", "wins-", "", "a:b", "a.b", "a b", "x".repeat(33)))
            assertThrows(IllegalArgumentException.class, () -> namespace.ranking(bad, RankingInfo.of("x", null), n -> List.of()), bad);
        assertThrows(NullPointerException.class, () -> namespace.ranking("ok", null, n -> List.of()));
        assertThrows(NullPointerException.class, () -> namespace.ranking("ok", RankingInfo.of("x", null), null));
        namespace.ranking("1v1-wins", RankingInfo.of("x", null), n -> List.of());
        namespace.ranking("x".repeat(32), RankingInfo.of("x", null), n -> List.of());
    }

    @Test
    void givenOwnerDisabled_whenRankingsAsked_thenGone_andAnotherPluginCannotTakeTheNamespaceWhileEnabled() {
        Plugin thief = PlaceholderTesting.plugin("Thief");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> placeholders.register(thief, "paddock"));
        assertTrue(refused.getMessage().contains("VystormPaddock"), refused.getMessage());
        assertEquals(1, testing.disable(paddock));
        assertTrue(testing.rankings().isEmpty());
        assertEquals(List.of(), placeholders.top("paddock:race-wins", 3));
        assertEquals("%vystorm_paddock_top_race-wins_1_name%", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"));
        placeholders.register(paddock, "paddock").ranking("race-wins", RankingInfo.of("Race wins", null), n -> wins());
        assertEquals("Alex", testing.resolve(null, "%vystorm_paddock_top_race-wins_1_name%"), "the same plugin declares again after a reload");
    }

    @Test
    void givenNamespaceNotOfCore_whenRankingDeclared_thenUnsupported() {
        PlaceholderNamespace foreign = new PlaceholderNamespace() {
            @Override public String name() { return "x"; }
            @Override public Plugin owner() { return paddock; }
            @Override public PlaceholderNamespace add(PlaceholderValue value) { return this; }
            @Override public PlaceholderNamespace fallback(PlaceholderResolver resolver) { return this; }
            @Override public PlaceholderNamespace fallback(PlaceholderResolver resolver, boolean asyncSafe) { return this; }
            @Override public boolean remove(String key) { return false; }
            @Override public List<PlaceholderValue> values() { return List.of(); }
            @Override public java.util.Optional<PlaceholderValue> value(String key) { return java.util.Optional.empty(); }
            @Override public boolean hasFallback() { return false; }
            @Override public boolean registered() { return true; }
        };
        assertThrows(UnsupportedOperationException.class, () -> foreign.ranking("a", RankingInfo.of("a", null), n -> List.of()));
        assertFalse(foreign.removeRanking("a"));
        assertTrue(foreign.rankings().isEmpty());
    }

    @Test
    void givenBridgeRequest_whenAskedLikePlaceholderApi_thenSamePathAsEveryOtherValue() {
        assertEquals("Alex", placeholders.request(null, null, "paddock_top_race-wins_1_name"));
        assertEquals("1", placeholders.request(alex, null, "paddock_rank_race-wins"));
        assertEquals("Alex", placeholders.value(null, "paddock", "top_race-wins", "1", "name"));
    }
}
