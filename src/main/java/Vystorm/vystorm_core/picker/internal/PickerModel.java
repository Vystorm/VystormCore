package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPicks;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The state of one player picker without Bukkit (design 3.2 to 3.6): the candidates of every source, the current tab,
 * search text, page and the ticked rows. The runtime feeds it {@link Online} views (with the viewer's
 * {@code canSee}/vanish answer already taken) and the known-player answers; {@link #refresh} rebuilds the candidate
 * lists, the filter hides rows there, the rule only runs for the rows of the page that is shown. Server thread.
 * Core-internal.
 */
public final class PickerModel {
    /** One online player as the viewer sees them; {@code distance} is -1 in another world. */
    public record Online(UUID id, String name, boolean visible, double distance, String skin) {}

    /** One row candidate. {@code distance} is -1 when unknown or in another world; {@code skin} may be null. */
    public record Candidate(UUID id, String name, boolean online, long lastSeen, double distance, String skin) {
        public PickedPlayer picked() { return new PickedPlayer(id, name, online, lastSeen); }
    }

    /** One shown row: the rule's answer and whether it is ticked. */
    public record Row(Candidate candidate, PickVerdict verdict, boolean chosen) {
        public boolean allowed() { return verdict.allowed(); }
    }

    /** Where the rows come from (the runtime: server and known-player store). */
    public interface Sources {
        List<Online> online();
        /** Offline candidates seen recently, newest first (store; not findable ones left out). */
        List<Candidate> recent();
        /** Known players whose name starts with {@code prefix} (store; not findable ones left out). */
        List<Candidate> search(String prefix, int limit);
        /** A candidate for a ticked id that is in no list (preselected), or null. */
        Candidate lookup(UUID id);
    }

    /** The limits from Core's config. */
    public record Limits(int searchMinChars, int searchMaxHits, int recentLimit) {
        public static final Limits DEFAULTS = new Limits(3, 20, 200);
    }

    /** State of the search tab. */
    public enum SearchState { EMPTY, SHORT, OK }

    /** Outcome of a click on a row. */
    public enum Toggle { ON, OFF, FULL, DENIED, UNKNOWN }

    public static final int CHEST_PAGE = 45;
    public static final int SCREEN_PAGE = 200;
    public static final int MAX_QUERY = 32;

    private final UUID viewer;
    private final List<PlayerSource> sources;
    private final boolean multiple;
    private final int max;
    private final boolean includeSelf;
    private final double nearbyRadius;
    private final Set<UUID> preselected;
    private final Predicate<Candidate> filter;
    private final Function<Candidate, PickVerdict> rule;
    private final Limits limits;

    private final Map<PlayerSource, List<Candidate>> lists = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> chosen = new LinkedHashSet<>();
    private final Map<UUID, Candidate> chosenInfo = new LinkedHashMap<>();
    private PlayerSource tab;
    private String query = "";
    private int page;

    public PickerModel(UUID viewer, List<PlayerSource> sources, boolean multiple, int max, boolean includeSelf, double nearbyRadius,
                       Set<UUID> preselected, Predicate<Candidate> filter, Function<Candidate, PickVerdict> rule, Limits limits) {
        this.viewer = viewer;
        this.sources = List.copyOf(sources);
        if (this.sources.isEmpty()) throw new IllegalArgumentException("no sources");
        this.multiple = multiple;
        this.max = Math.max(1, max);
        this.includeSelf = includeSelf;
        this.nearbyRadius = nearbyRadius;
        this.preselected = Set.copyOf(preselected);
        this.filter = Objects.requireNonNull(filter);
        this.rule = Objects.requireNonNull(rule);
        this.limits = Objects.requireNonNull(limits);
        chosen.addAll(this.preselected);
        tab = this.sources.getFirst();
    }

    // ------------------------------------------------------------------ candidates

    /** Rebuilds every list from {@code src} (filter applied; the rule is not run here). */
    public void refresh(Sources src) {
        lists.clear();
        List<Candidate> online = new ArrayList<>();
        Set<UUID> visibleOnline = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Online o : src.online()) {
            if (!o.visible() || (!includeSelf && o.id().equals(viewer))) continue;
            Candidate c = new Candidate(o.id(), o.name(), true, now, o.distance(), o.skin());
            visibleOnline.add(o.id());
            if (shown(c)) online.add(c);
        }
        online.sort(BY_NAME);
        if (sources.contains(PlayerSource.ONLINE)) lists.put(PlayerSource.ONLINE, online);
        if (sources.contains(PlayerSource.NEARBY)) {
            List<Candidate> near = new ArrayList<>();
            for (Candidate c : online) if (c.distance() >= 0 && c.distance() <= nearbyRadius) near.add(c);
            near.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(BY_NAME));
            lists.put(PlayerSource.NEARBY, near);
        }
        if (sources.contains(PlayerSource.RECENT)) {
            List<Candidate> recent = new ArrayList<>();
            for (Candidate c : src.recent()) {
                if (visibleOnline.contains(c.id()) || (!includeSelf && c.id().equals(viewer)) || !shown(c)) continue;
                recent.add(c);
                if (recent.size() >= limits.recentLimit()) break;
            }
            lists.put(PlayerSource.RECENT, recent);
        }
        if (sources.contains(PlayerSource.SEARCH)) lists.put(PlayerSource.SEARCH, search(src, online, visibleOnline));
        // ticked rows remember what they looked like (also when the player leaves: handed over as offline)
        Set<UUID> listed = new HashSet<>();
        for (List<Candidate> list : lists.values()) for (Candidate c : list) if (chosen.contains(c.id())) { chosenInfo.put(c.id(), c); listed.add(c.id()); }
        for (UUID id : chosen) {
            if (listed.contains(id)) continue;
            Candidate c = src.lookup(id);
            Candidate old = chosenInfo.get(id);
            if (c != null) chosenInfo.put(id, c);
            else if (old != null && old.online() && !visibleOnline.contains(id))
                chosenInfo.put(id, new Candidate(old.id(), old.name(), false, now, -1, old.skin()));   // left: handed over as offline
        }
        if (!sources.contains(tab)) tab = sources.getFirst();
    }

    private boolean shown(Candidate c) {
        try {
            return filter.test(c);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private List<Candidate> search(Sources src, List<Candidate> online, Set<UUID> visibleOnline) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Candidate> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Candidate c : online) if (c.name().toLowerCase(Locale.ROOT).startsWith(q)) out.add(c);
        if (q.length() < limits.searchMinChars()) return out;
        int offline = 0;
        for (Candidate c : src.search(q, limits.searchMaxHits() + online.size())) {
            if (visibleOnline.contains(c.id()) || (!includeSelf && c.id().equals(viewer)) || !shown(c)) continue;
            out.add(c);
            if (++offline >= limits.searchMaxHits()) break;
        }
        return out;
    }

    static final Comparator<Candidate> BY_NAME = Comparator.comparing((Candidate c) -> c.name().toLowerCase(Locale.ROOT)).thenComparing(Candidate::id);

    // ------------------------------------------------------------------ tabs, search, pages

    public List<PlayerSource> sources() { return sources; }

    public PlayerSource tab() { return tab; }

    /** Number of rows of a source (filter applied). */
    public int count(PlayerSource s) {
        List<Candidate> l = lists.get(s);
        return l == null ? 0 : l.size();
    }

    /** Tabs to draw: none with one source; otherwise sources with rows, the search tab and the current tab. */
    public List<PlayerSource> visibleTabs() {
        if (sources.size() == 1) return List.of();
        List<PlayerSource> out = new ArrayList<>();
        for (PlayerSource s : sources) if (count(s) > 0 || s == PlayerSource.SEARCH || s == tab) out.add(s);
        return out;
    }

    /**
     * The tab the picker opens on: the first tab with a row the viewer can choose (Nearby first, then the sources in
     * order), so a Nearby tab that holds only the viewer's own refused row is skipped; without any choosable row the
     * first tab with rows, else the first source. The rule runs for at most one screen page per tab.
     */
    public PlayerSource firstTab() {
        List<PlayerSource> order = new ArrayList<>();
        if (sources.contains(PlayerSource.NEARBY)) order.add(PlayerSource.NEARBY);
        for (PlayerSource s : sources) if (s != PlayerSource.SEARCH && !order.contains(s)) order.add(s);
        PlayerSource withRows = null;
        for (PlayerSource s : order) {
            if (count(s) == 0) continue;
            if (withRows == null) withRows = s;
            if (choosable(s)) return s;
        }
        return withRows != null ? withRows : sources.getFirst();
    }

    /** True when a row of this source can be clicked: its rule says yes, or it is ticked (unticking always works). */
    private boolean choosable(PlayerSource s) {
        List<Candidate> list = lists.getOrDefault(s, List.of());
        for (int i = 0; i < Math.min(list.size(), SCREEN_PAGE); i++) {
            Candidate c = list.get(i);
            if (chosen.contains(c.id()) || verdict(c).allowed()) return true;
        }
        return false;
    }

    public boolean select(PlayerSource s) {
        if (!sources.contains(s)) return false;
        if (s != tab) page = 0;
        tab = s;
        return true;
    }

    public String query() { return query; }

    /** Sets the search text (trimmed, at most {@value #MAX_QUERY} characters); the caller refreshes. */
    public void query(String text) {
        String q = text == null ? "" : text.strip();
        if (q.length() > MAX_QUERY) q = q.substring(0, MAX_QUERY);
        if (!q.equals(query)) page = 0;
        query = q;
    }

    public SearchState searchState() {
        if (query.isEmpty()) return SearchState.EMPTY;
        return query.length() < limits.searchMinChars() ? SearchState.SHORT : SearchState.OK;
    }

    public Limits limits() { return limits; }

    public int page() { return page; }

    public int pages(int pageSize) { return Math.max(1, (count(tab) + pageSize - 1) / pageSize); }

    /** Keeps the page within the rows that are there now (the list may have shrunk). */
    public void fit(int pageSize) { page = Math.max(0, Math.min(page, pages(pageSize) - 1)); }

    public void page(int page, int pageSize) { this.page = Math.max(0, Math.min(page, pages(pageSize) - 1)); }

    /** The rows of the current tab and page, with the rule's answer (the rule runs only for these). */
    public List<Row> rows(int pageSize) {
        fit(pageSize);
        List<Candidate> all = lists.getOrDefault(tab, List.of());
        int from = Math.min(all.size(), page * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        List<Row> out = new ArrayList<>(to - from);
        for (Candidate c : all.subList(from, to)) out.add(new Row(c, verdict(c), chosen.contains(c.id())));
        return out;
    }

    /** First row number (1-based) and last of the current page, for "1 to 45 of 120". */
    public int[] span(int pageSize) {
        fit(pageSize);
        int total = count(tab);
        int from = Math.min(total, page * pageSize);
        return new int[]{total == 0 ? 0 : from + 1, Math.min(total, from + pageSize), total};
    }

    PickVerdict verdict(Candidate c) {
        try {
            PickVerdict v = rule.apply(c);
            return v == null ? PickVerdict.ok() : v;
        } catch (RuntimeException failure) {
            return PickVerdict.deny("");
        }
    }

    /** The candidate with this id in any list. */
    public Candidate find(UUID id) {
        for (List<Candidate> list : lists.values()) for (Candidate c : list) if (c.id().equals(id)) return c;
        return chosenInfo.get(id);
    }

    // ------------------------------------------------------------------ choosing

    public boolean multiple() { return multiple; }

    public int max() { return max; }

    public int chosenCount() { return chosen.size(); }

    public boolean isChosen(UUID id) { return chosen.contains(id); }

    /** Multiple mode: ticks or unticks a row (denied rows and a full set refuse a new tick; unticking always works). */
    public Toggle toggle(UUID id) {
        if (chosen.contains(id)) {
            chosen.remove(id);
            chosenInfo.remove(id);
            return Toggle.OFF;
        }
        Candidate c = find(id);
        if (c == null) return Toggle.UNKNOWN;
        if (!verdict(c).allowed()) return Toggle.DENIED;
        if (chosen.size() >= max) return Toggle.FULL;
        chosen.add(id);
        chosenInfo.put(id, c);
        return Toggle.ON;
    }

    /** The ticked players for the hand-over (single mode: {@code single} instead of the ticks). */
    public PlayerPicks picks(Candidate single, PanelSession session) {
        List<PickedPlayer> list = new ArrayList<>();
        Set<UUID> added = new LinkedHashSet<>();
        Set<UUID> removed = new LinkedHashSet<>();
        if (single != null) {
            list.add(single.picked());
            added.add(single.id());
        } else {
            for (UUID id : chosen) {
                Candidate c = chosenInfo.get(id);
                list.add(c != null ? c.picked() : new PickedPlayer(id, id.toString().substring(0, 8), false, 0));
                if (!preselected.contains(id)) added.add(id);
            }
            for (UUID id : preselected) if (!chosen.contains(id)) removed.add(id);
        }
        return new PlayerPicks(list, added, removed, session);
    }

    /** The newly ticked players whose rule now says no (checked again right before the hand-over). */
    public List<Row> deniedAdded() {
        List<Row> out = new ArrayList<>();
        for (UUID id : chosen) {
            if (preselected.contains(id)) continue;
            Candidate c = chosenInfo.get(id);
            if (c == null) continue;
            PickVerdict v = verdict(c);
            if (!v.allowed()) out.add(new Row(c, v, true));
        }
        return out;
    }
}
