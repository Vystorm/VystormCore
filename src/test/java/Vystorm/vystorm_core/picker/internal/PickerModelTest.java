package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PlayerPicks;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class PickerModelTest {
    static final UUID VIEWER = id(0);
    static final long NOW = System.currentTimeMillis();

    static UUID id(int n) { return new UUID(0, n); }

    /** A fixed world: online players with distances, a recent list and a store for searches. */
    static final class World implements PickerModel.Sources {
        final List<PickerModel.Online> online = new ArrayList<>();
        final List<PickerModel.Candidate> recent = new ArrayList<>();
        final List<PickerModel.Candidate> store = new ArrayList<>();
        int searches;

        World online(int n, String name, double distance) { online.add(new PickerModel.Online(id(n), name, true, distance, null)); return this; }
        World vanished(int n, String name) { online.add(new PickerModel.Online(id(n), name, false, 3, null)); return this; }
        World known(int n, String name, long lastSeen) {
            PickerModel.Candidate c = new PickerModel.Candidate(id(n), name, false, lastSeen, -1, null);
            store.add(c);
            recent.add(c);
            recent.sort((a, b) -> Long.compare(b.lastSeen(), a.lastSeen()));
            return this;
        }

        @Override public List<PickerModel.Online> online() { return online; }
        @Override public List<PickerModel.Candidate> recent() { return recent; }
        @Override public List<PickerModel.Candidate> search(String prefix, int limit) {
            searches++;
            return store.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(PickerModel.BY_NAME).limit(limit).toList();
        }
        @Override public PickerModel.Candidate lookup(UUID id) { return store.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    }

    static PickerModel model(boolean multiple, int max, Set<UUID> preselected, Predicate<PickerModel.Candidate> filter, Function<PickerModel.Candidate, PickVerdict> rule) {
        return new PickerModel(VIEWER, List.of(PlayerSource.values()), multiple, max, false, 16, preselected, filter, rule, PickerModel.Limits.DEFAULTS);
    }

    static PickerModel single() { return model(false, 1, Set.of(), c -> true, c -> PickVerdict.ok()); }

    static World world() {
        return new World().online(0, "Me", 0).online(1, "bob", 30).online(2, "Alex", 5).online(3, "Cleo", -1).vanished(4, "Ghost")
                .known(10, "Alexandra", NOW - 2 * PickerText.DAY).known(11, "Alessia", NOW - 40 * PickerText.DAY).known(4, "Ghost", NOW - PickerText.DAY / 2);
    }

    static List<String> names(PickerModel m, int pageSize) { return m.rows(pageSize).stream().map(r -> r.candidate().name()).toList(); }

    @Test
    void givenOnlinePlayers_whenRefreshed_thenOnlineByNameWithoutSelfAndVanished() {
        PickerModel m = single();
        m.refresh(world());
        m.select(PlayerSource.ONLINE);
        assertEquals(List.of("Alex", "bob", "Cleo"), names(m, 200));
    }

    @Test
    void givenRadius_whenNearby_thenSameWorldWithinRadiusNearestFirst() {
        PickerModel m = single();
        m.refresh(world());
        m.select(PlayerSource.NEARBY);
        assertEquals(List.of("Alex"), names(m, 200), "bob is 30 blocks away, Cleo in another world");
        assertEquals(PlayerSource.NEARBY, m.firstTab(), "someone is close: the picker opens on Nearby");
    }

    /** Defect 5 of the first client look: the multi picker opened on Nearby where only the viewer's own refused row was. */
    @Test
    void givenOnlyARefusedRowNearby_whenFirstTab_thenTheFirstTabWithAChoosableRow() {
        PickerModel m = new PickerModel(VIEWER, List.of(PlayerSource.values()), true, 10, true, 16, Set.of(), c -> true,
                c -> c.id().equals(VIEWER) ? PickVerdict.deny("not yourself") : PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        m.refresh(new World().online(0, "Me", 0).online(1, "bob", 50).known(10, "Alexandra", NOW - PickerText.DAY));
        assertEquals(1, m.count(PlayerSource.NEARBY), "only the viewer is close");
        assertEquals(PlayerSource.ONLINE, m.firstTab(), "Online has bob, who can be chosen");
        PickerModel alone = new PickerModel(VIEWER, List.of(PlayerSource.values()), true, 10, true, 16, Set.of(), c -> true,
                c -> c.id().equals(VIEWER) ? PickVerdict.deny("not yourself") : PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        alone.refresh(new World().online(0, "Me", 0).known(10, "Alexandra", NOW - PickerText.DAY));
        assertEquals(PlayerSource.RECENT, alone.firstTab(), "nobody else online: the recent players");
        PickerModel nothing = new PickerModel(VIEWER, List.of(PlayerSource.values()), true, 10, true, 16, Set.of(), c -> true,
                c -> PickVerdict.deny("no"), PickerModel.Limits.DEFAULTS);
        nothing.refresh(new World().online(0, "Me", 0).online(1, "bob", 50));
        assertEquals(PlayerSource.NEARBY, nothing.firstTab(), "nothing choosable anywhere: the first tab with rows, as before");
    }

    @Test
    void givenNobodyClose_whenFirstTab_thenFirstSourceWithRows() {
        PickerModel m = single();
        m.refresh(new World().online(1, "bob", 50));
        assertEquals(PlayerSource.ONLINE, m.firstTab());
    }

    @Test
    void givenRecentAndVanished_whenRecent_thenOfflineNewestFirstAndVanishedLookOffline() {
        PickerModel m = single();
        m.refresh(world());
        m.select(PlayerSource.RECENT);
        assertEquals(List.of("Ghost", "Alexandra", "Alessia"), names(m, 200));
        assertFalse(m.rows(200).getFirst().candidate().online(), "a vanished player is never shown as online");
    }

    @Test
    void givenSearch_whenShortOrLong_thenOnlineFirstAndOfflineFromMinChars() {
        PickerModel m = single();
        World w = world();
        m.query("  AL ");
        m.refresh(w);
        m.select(PlayerSource.SEARCH);
        assertEquals("AL", m.query());
        assertEquals(PickerModel.SearchState.SHORT, m.searchState());
        assertEquals(List.of("Alex"), names(m, 200), "two letters: online only");
        assertEquals(0, w.searches, "no store search below the minimum");
        m.query("ale");
        m.refresh(w);
        assertEquals(PickerModel.SearchState.OK, m.searchState());
        assertEquals(List.of("Alex", "Alessia", "Alexandra"), names(m, 200));
        m.query("");
        m.refresh(w);
        assertEquals(PickerModel.SearchState.EMPTY, m.searchState());
        assertTrue(m.rows(200).isEmpty());
    }

    @Test
    void givenManyStoreHits_whenSearched_thenCappedAtMaxHits() {
        World w = new World();
        for (int i = 0; i < 40; i++) w.known(100 + i, "Zed" + i, NOW - i);
        PickerModel m = single();
        m.query("zed");
        m.refresh(w);
        m.select(PlayerSource.SEARCH);
        assertEquals(PickerModel.Limits.DEFAULTS.searchMaxHits(), m.count(PlayerSource.SEARCH));
    }

    @Test
    void givenFilterAndRule_whenRendered_thenFilterHidesAndRuleGreysWithReason() {
        PickerModel m = model(false, 1, Set.of(), c -> !c.name().equals("bob"), c -> c.name().equals("Cleo") ? PickVerdict.deny("busy") : PickVerdict.ok());
        m.refresh(world());
        m.select(PlayerSource.ONLINE);
        List<PickerModel.Row> rows = m.rows(200);
        assertEquals(List.of("Alex", "Cleo"), rows.stream().map(r -> r.candidate().name()).toList());
        assertTrue(rows.get(0).allowed());
        assertFalse(rows.get(1).allowed());
        assertEquals("busy", rows.get(1).verdict().reason());
        assertEquals(2, m.count(PlayerSource.ONLINE));
    }

    @Test
    void givenThrowingRuleOrFilter_whenRendered_thenRowDeniedOrHidden() {
        PickerModel m = model(false, 1, Set.of(), c -> { if (c.name().equals("bob")) throw new IllegalStateException(); return true; },
                c -> { if (c.name().equals("Alex")) throw new IllegalStateException(); return PickVerdict.ok(); });
        m.refresh(world());
        m.select(PlayerSource.ONLINE);
        assertEquals(List.of("Alex", "Cleo"), names(m, 200));
        assertFalse(m.rows(200).getFirst().allowed());
    }

    @Test
    void givenMultiple_whenToggled_thenMaxDeniedAndUntickWork() {
        PickerModel m = model(true, 2, Set.of(), c -> true, c -> c.name().equals("Cleo") ? PickVerdict.deny("no") : PickVerdict.ok());
        m.refresh(world());
        assertEquals(PickerModel.Toggle.ON, m.toggle(id(2)));
        assertEquals(PickerModel.Toggle.DENIED, m.toggle(id(3)));
        assertEquals(PickerModel.Toggle.ON, m.toggle(id(1)));
        assertEquals(PickerModel.Toggle.FULL, m.toggle(id(10)));
        assertEquals(PickerModel.Toggle.OFF, m.toggle(id(2)));
        assertEquals(PickerModel.Toggle.ON, m.toggle(id(10)));
        assertEquals(PickerModel.Toggle.UNKNOWN, m.toggle(id(999)));
        PlayerPicks picks = m.picks(null, new PickReturnSession());
        assertEquals(List.of("bob", "Alexandra"), picks.chosen().stream().map(p -> p.name()).toList());
        assertEquals(Set.of(id(1), id(10)), picks.added());
    }

    @Test
    void givenPreselected_whenUnticked_thenRemovedAndKeptOnesNotAdded() {
        PickerModel m = model(true, 10, Set.of(id(2), id(11)), c -> true, c -> PickVerdict.ok());
        m.refresh(world());
        m.select(PlayerSource.ONLINE);
        assertTrue(m.rows(200).getFirst().chosen(), "Alex starts ticked");
        m.toggle(id(2));
        m.toggle(id(1));
        PlayerPicks picks = m.picks(null, new PickReturnSession());
        assertEquals(Set.of(id(1)), picks.added());
        assertEquals(Set.of(id(2)), picks.removed());
        assertEquals(List.of("Alessia", "bob"), picks.chosen().stream().map(p -> p.name()).toList(), "preselected Alessia is not on a tab but stays chosen");
    }

    @Test
    void givenChosenPlayerWhoLeaves_whenHandedOver_thenStillChosen() {
        PickerModel m = model(true, 5, Set.of(), c -> true, c -> PickVerdict.ok());
        World w = world();
        m.refresh(w);
        m.toggle(id(1));
        w.online.removeIf(o -> o.id().equals(id(1)));
        m.refresh(w);
        assertEquals(List.of("bob"), m.picks(null, new PickReturnSession()).chosen().stream().map(p -> p.name()).toList());
        assertFalse(m.picks(null, new PickReturnSession()).chosen().getFirst().online(), "handed over as offline");
    }

    @Test
    void givenRuleChangedAfterTick_whenCheckedAgain_thenDeniedAddedListed() {
        boolean[] closed = {false};
        PickerModel m = model(true, 5, Set.of(id(2)), c -> true, c -> closed[0] ? PickVerdict.deny("closed") : PickVerdict.ok());
        m.refresh(world());
        m.toggle(id(1));
        closed[0] = true;
        assertEquals(List.of("bob"), m.deniedAdded().stream().map(r -> r.candidate().name()).toList(), "preselected Alex is not checked again");
    }

    @Test
    void givenManyRows_whenPaged_thenChestAndScreenPageSizes() {
        World w = new World();
        for (int i = 0; i < 230; i++) w.online(100 + i, String.format("P%03d", i), -1);
        PickerModel m = single();
        m.refresh(w);
        m.select(PlayerSource.ONLINE);
        assertEquals(6, m.pages(PickerModel.CHEST_PAGE));
        assertEquals(2, m.pages(PickerModel.SCREEN_PAGE));
        m.page(5, PickerModel.CHEST_PAGE);
        assertEquals(230 - 225, m.rows(PickerModel.CHEST_PAGE).size());
        assertEquals("P225", m.rows(PickerModel.CHEST_PAGE).getFirst().candidate().name());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{226, 230, 230}, m.span(PickerModel.CHEST_PAGE));
        m.page(99, PickerModel.CHEST_PAGE);
        assertEquals(5, m.page());
        m.select(PlayerSource.RECENT);
        assertEquals(0, m.page(), "a tab change starts on page 1");
    }

    @Test
    void givenEmptySources_whenTabs_thenHiddenButSearchAndCurrentStay() {
        PickerModel m = single();
        m.refresh(new World().online(1, "bob", 50));
        m.select(PlayerSource.ONLINE);
        assertEquals(List.of(PlayerSource.ONLINE, PlayerSource.SEARCH), m.visibleTabs());
        PickerModel one = new PickerModel(VIEWER, List.of(PlayerSource.ONLINE), false, 1, false, 16, Set.of(), c -> true, c -> PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        one.refresh(new World().online(1, "bob", 50));
        assertTrue(one.visibleTabs().isEmpty(), "one source: no tab row");
    }

    @Test
    void givenIncludeSelf_whenOnline_thenViewerListed() {
        PickerModel m = new PickerModel(VIEWER, List.of(PlayerSource.ONLINE), false, 1, true, 16, Set.of(), c -> true, c -> PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        m.refresh(world());
        assertTrue(names(m, 200).contains("Me"));
    }

    @Test
    void givenLastSeen_whenBucketed_thenCoarse() {
        assertEquals(PickerText.Seen.TODAY, PickerText.seen(NOW - 1000, NOW));
        assertEquals(PickerText.Seen.WEEK, PickerText.seen(NOW - 3 * PickerText.DAY, NOW));
        assertEquals(PickerText.Seen.MONTH, PickerText.seen(NOW - 20 * PickerText.DAY, NOW));
        assertEquals(PickerText.Seen.EARLIER, PickerText.seen(NOW - 90 * PickerText.DAY, NOW));
        assertEquals(PickerText.Seen.UNKNOWN, PickerText.seen(0, NOW));
        PickerModel.Candidate off = new PickerModel.Candidate(id(1), "x", false, NOW - 3 * PickerText.DAY, -1, null);
        assertEquals("pickers.row.seen-week", PickerText.status(off, false, NOW, java.time.ZoneOffset.UTC).key());
        assertEquals("pickers.row.seen-at", PickerText.status(off, true, NOW, java.time.ZoneOffset.UTC).key());
        PickerModel.Candidate near = new PickerModel.Candidate(id(1), "x", true, NOW, 12.4, null);
        PickerText.Line l = PickerText.status(near, false, NOW, java.time.ZoneOffset.UTC);
        assertEquals("pickers.row.online-near", l.key());
        assertEquals(12L, l.args()[1]);
    }

    /** A plain session store for the hand-over. */
    static final class PickReturnSession implements Vystorm.vystorm_core.panel.PanelSession {
        final java.util.Map<String, Object> map = new java.util.HashMap<>();
        @Override public int id() { return 0; }
        @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) map.get(key); }
        @Override public void put(String key, Object value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }
}
