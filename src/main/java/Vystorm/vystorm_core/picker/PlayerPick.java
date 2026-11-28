package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Description of one player pick (0.71.0): title, tabs, filter and rule, single or multiple choice and what happens
 * with the choice. Built once per click with {@link #builder(Object)}; immutable. Texts are String, {@code Lang.ref}
 * or Adventure {@code Component}.
 *
 * <pre>{@code
 * PlayerPick pick = PlayerPick.builder(lang.ref("pick.members.title"))
 *         .sources(PlayerSource.ONLINE, PlayerSource.NEARBY, PlayerSource.RECENT, PlayerSource.SEARCH)
 *         .filter((viewer, c) -> !area.isMember(c.id()))                      // hidden rows
 *         .rule((viewer, c) -> c.online() ? PickVerdict.ok() : PickVerdict.deny(lang.ref("pick.online-only")))
 *         .multiple(10)
 *         .onPick((viewer, picks) -> addMembers(viewer, picks.added()))     // returns a panel Result
 *         .build();
 * return core.gui().pickers().players(action, pick);
 * }</pre>
 *
 * @since 0.71.0
 */
public final class PlayerPick {
    /** Default and largest timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120), MAX_TIMEOUT = Duration.ofSeconds(600);

    /** What happens with the choice. Server thread; return {@code Result.error(..)} to keep the picker open with the message. */
    @FunctionalInterface
    public interface Handler { Result picked(Player viewer, PlayerPicks picks); }

    /** A row that is shown but may not be chosen gets a reason. Server thread; runs once per row and render, keep it cheap. */
    @FunctionalInterface
    public interface Rule { PickVerdict check(Player viewer, PickedPlayer candidate); }

    /** Rows that are not shown at all. Server thread; keep it cheap. */
    @FunctionalInterface
    public interface Filter { boolean show(Player viewer, PickedPlayer candidate); }

    /** Told how the flow ended (also after {@link PickEnd#DONE}). Server thread. */
    @FunctionalInterface
    public interface EndHandler { void ended(Player viewer, PickEnd end); }

    private final Object title;
    private final Object subtitle;
    private final List<PlayerSource> sources;
    private final int nearbyRadius;
    private final int recentDays;
    private final boolean includeSelf;
    private final Filter filter;
    private final Rule rule;
    private final int max;
    private final boolean multiple;
    private final Set<UUID> preselected;
    private final Object confirmLabel;
    private final Duration timeout;
    private final Handler onPick;
    private final EndHandler onEnd;

    private PlayerPick(Builder b) {
        title = b.title;
        subtitle = b.subtitle;
        sources = List.copyOf(b.sources.isEmpty() ? List.of(PlayerSource.values()) : new ArrayList<>(b.sources));
        nearbyRadius = b.nearbyRadius;
        recentDays = b.recentDays;
        includeSelf = b.includeSelf;
        filter = b.filter;
        rule = b.rule;
        max = b.max;
        multiple = b.multiple;
        preselected = Set.copyOf(b.preselected);
        confirmLabel = b.confirmLabel;
        timeout = b.timeout;
        onPick = b.onPick;
        onEnd = b.onEnd;
    }

    public static Builder builder(Object title) { return new Builder(title); }

    public Object title() { return title; }
    /** Null without one. */
    public Object subtitle() { return subtitle; }
    public List<PlayerSource> sources() { return sources; }
    /** Blocks; 0 = Core's default ({@code pickers.players.default-nearby}, 16). */
    public int nearbyRadius() { return nearbyRadius; }
    /** Days; 0 = Core's default ({@code pickers.players.recent-days}, 30). */
    public int recentDays() { return recentDays; }
    public boolean includeSelf() { return includeSelf; }
    public Filter filter() { return filter; }
    public Rule rule() { return rule; }
    /** 1 = single choice; more = multiple choice with at most this many ticked rows. */
    public int max() { return max; }
    /** Whether rows are ticked and handed over with the footer button. */
    public boolean multiple() { return multiple; }
    public Set<UUID> preselected() { return preselected; }
    /** Null = Core's "Done". */
    public Object confirmLabel() { return confirmLabel; }
    public Duration timeout() { return timeout; }
    public Handler onPick() { return onPick; }
    /** Null without one. */
    public EndHandler onEnd() { return onEnd; }

    public static final class Builder {
        private final Object title;
        private Object subtitle;
        private final LinkedHashSet<PlayerSource> sources = new LinkedHashSet<>();
        private int nearbyRadius;
        private int recentDays;
        private boolean includeSelf;
        private Filter filter = (v, c) -> true;
        private Rule rule = (v, c) -> PickVerdict.ok();
        private int max = 1;
        private boolean multiple;
        private final Set<UUID> preselected = new LinkedHashSet<>();
        private Object confirmLabel;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Handler onPick;
        private EndHandler onEnd;

        private Builder(Object title) { this.title = Objects.requireNonNull(title, "title"); }

        public Builder subtitle(Object subtitle) { this.subtitle = subtitle; return this; }

        /** The tabs, in this order (each source once). Default: all four. */
        public Builder sources(PlayerSource... sources) {
            this.sources.clear();
            for (PlayerSource s : sources) this.sources.add(Objects.requireNonNull(s));
            return this;
        }

        /** Radius of {@link PlayerSource#NEARBY} in blocks (1 or more), capped by {@code pickers.players.max-nearby} (64). */
        public Builder nearbyRadius(int blocks) {
            if (blocks < 1) throw new IllegalArgumentException("nearbyRadius must be at least 1");
            this.nearbyRadius = blocks;
            return this;
        }

        /** Days of {@link PlayerSource#RECENT} (1 or more), capped by {@code known-players.forget-after-days}. */
        public Builder recentDays(int days) {
            if (days < 1) throw new IllegalArgumentException("recentDays must be at least 1");
            this.recentDays = days;
            return this;
        }

        /** Whether the viewer appears in the rows (default false). */
        public Builder includeSelf(boolean include) { this.includeSelf = include; return this; }

        public Builder filter(Filter filter) { this.filter = Objects.requireNonNull(filter); return this; }

        public Builder rule(Rule rule) { this.rule = Objects.requireNonNull(rule); return this; }

        /** One choice ends the pick (default). */
        public Builder single() { this.max = 1; this.multiple = false; return this; }

        /** Rows are ticked and the footer button hands them over; at most {@code max} ticked rows (1 to 2000). */
        public Builder multiple(int max) {
            if (max < 1 || max > 2000) throw new IllegalArgumentException("multiple(max) needs 1..2000");
            this.max = max;
            this.multiple = true;
            return this;
        }

        /** Rows that start ticked (multiple mode; "edit this set" pickers). */
        public Builder preselected(Set<UUID> ids) {
            this.preselected.clear();
            for (UUID id : ids) this.preselected.add(Objects.requireNonNull(id));
            return this;
        }

        /** Label of the footer button in multiple mode. */
        public Builder confirmLabel(Object label) { this.confirmLabel = label; return this; }

        /** Idle time until the picker closes (from the last click; 10 s to 600 s, default 120 s). */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            long s = timeout.toSeconds();
            this.timeout = Duration.ofSeconds(Math.max(10, Math.min(MAX_TIMEOUT.toSeconds(), s)));
            return this;
        }

        public Builder onPick(Handler handler) { this.onPick = Objects.requireNonNull(handler); return this; }

        public Builder onEnd(EndHandler handler) { this.onEnd = handler; return this; }

        public PlayerPick build() {
            if (onPick == null) throw new IllegalStateException("PlayerPick needs onPick(...)");
            if (!multiple && !preselected.isEmpty()) throw new IllegalStateException("preselected(...) needs multiple(max)");
            return new PlayerPick(this);
        }
    }
}
