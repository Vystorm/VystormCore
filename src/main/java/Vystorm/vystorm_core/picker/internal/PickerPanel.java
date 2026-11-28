package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * The picker as a panel (design 3.4): a row of tab buttons (the server keeps the tab, screens would keep their own),
 * the search field on the Search tab, the player list with heads, status line, "chosen" badge and the rule's reason,
 * and the footer Back / Previous / Next / Done. Rows are {@code p:<uuid>}. Clicking a row picks it (single) or ticks it
 * (multiple). Every handler checks that the flow is still the one the panel was opened for. Core-internal.
 */
final class PickerPanel {
    static final String LIST = "players";
    static final String SEARCH = "search";

    private PickerPanel() {}

    static Panel render(PickerRuntime rt, PanelContext ctx) {
        Player viewer = ctx.player();
        Lang lang = PickerRuntime.lang();
        PlayerPickFlow flow = rt.flow(viewer);
        if (flow == null || flow.ended || !flow.token.equals(ctx.arg()))
            return Panel.page(lang.component(viewer, "pickers.title")).children(Ui.text(lang.component(viewer, "pickers.closed")));
        flow.model.refresh(rt.sources(flow));
        return build(flow, viewer, rt.exactLastSeen(viewer), System.currentTimeMillis(), ZoneId.systemDefault(), KnownPlayers.enabled(), new Handlers(rt, flow));
    }

    /** What the buttons do; separate so tests can render without a runtime. */
    record Handlers(PickerRuntime rt, PlayerPickFlow flow) {
        Result tab(PanelAction a, PlayerSource s) {
            if (!live()) return gone(a);
            rt.touch(flow);
            flow.model.select(s);
            // on a screen the Search tab asks for the name at once: one prompt, like the chest's search item
            if (s == PlayerSource.SEARCH && flow.route == PlayerPickFlow.Route.SCREEN)
                Vystorm.vystorm_core.panel.internal.PanelOpener.typeNext(flow.viewer, SEARCH);
            a.refresh();
            return Result.ok();
        }

        Result search(PanelAction a, String text) {
            if (!live()) return gone(a);
            rt.touch(flow);
            flow.model.query(text);
            flow.model.select(PlayerSource.SEARCH);
            a.refresh();
            return Result.ok();
        }

        Result page(PanelAction a, int delta) {
            if (!live()) return gone(a);
            rt.touch(flow);
            flow.model.page(flow.model.page() + delta, PickerModel.SCREEN_PAGE);
            a.refresh();
            return Result.ok();
        }

        Result row(PanelAction a, String key) {
            if (!live()) return gone(a);
            rt.touch(flow);
            UUID id = parse(key);
            PickerModel.Candidate c = id == null ? null : flow.model.find(id);
            Player viewer = flow.viewer;
            if (c == null) { a.refresh(); return Result.error(PickerRuntime.lang().plain(viewer, "pickers.gone")); }
            if (!flow.model.multiple()) return handOver(a, c);
            return switch (flow.model.toggle(id)) {
                case ON, OFF -> { a.refresh(); yield Result.ok(); }
                case FULL -> Result.error(PickerRuntime.lang().plain(viewer, "pickers.full", "max", flow.model.max()));
                case DENIED -> Result.error(PickerRuntime.plain(viewer, flow.model.verdict(c).reason()));
                case UNKNOWN -> { a.refresh(); yield Result.error(PickerRuntime.lang().plain(viewer, "pickers.gone")); }
            };
        }

        Result done(PanelAction a) {
            if (!live()) return gone(a);
            rt.touch(flow);
            return handOver(a, null);
        }

        Result back(PanelAction a) {
            if (!live()) return gone(a);
            rt.end(flow, PickEnd.BACK, false);
            a.closeAndThen(p -> rt.returnLater(flow, null));
            return Result.ok();
        }

        private Result handOver(PanelAction a, PickerModel.Candidate single) {
            Result r = rt.finish(flow, single);
            if (!flow.ended) { a.refresh(); return r; }
            if (rt.flow(flow.viewer) != null) return Result.ok();   // onPick opened the next picker: it replaced this one
            a.closeAndThen(p -> rt.returnLater(flow, r));
            return Result.ok();
        }

        private boolean live() { return !flow.ended && rt.flow(flow.viewer) == flow; }

        private Result gone(PanelAction a) {
            a.closeAndThen(p -> { });
            return Result.error(PickerRuntime.lang().plain(flow.viewer, "pickers.closed"));
        }
    }

    static UUID parse(String key) {
        if (key == null || !key.startsWith("p:")) return null;
        try { return UUID.fromString(key.substring(2)); } catch (IllegalArgumentException bad) { return null; }
    }

    static Panel build(PlayerPickFlow flow, Player viewer, boolean exact, long now, ZoneId zone, boolean storeOn, Handlers h) {
        Lang lang = PickerRuntime.lang();
        PickerModel m = flow.model;
        Panel panel = Panel.page(PickerRuntime.component(viewer, flow.pick.title())).size(Panel.Size.LARGE);
        if (flow.pick.subtitle() != null) panel.subtitle(PickerRuntime.component(viewer, flow.pick.subtitle()));
        List<PlayerSource> tabs = m.visibleTabs();
        if (!tabs.isEmpty()) {
            Ui.Box row = Ui.row().gap(4).wrap();
            for (PlayerSource s : tabs) {
                Ui.Button b = Ui.button("tab-" + s.name().toLowerCase(Locale.ROOT))
                        .label(s == PlayerSource.SEARCH ? lang.component(viewer, "pickers.tab.search") : lang.component(viewer, PickerText.tabKey(s), "count", m.count(s)))
                        .onClick(a -> h.tab(a, s));
                if (s == m.tab()) b.primary(); else b.ghost();
                row.child(b);
            }
            panel.child(row);
        }
        if (m.tab() == PlayerSource.SEARCH) {
            panel.child(Ui.input(SEARCH, lang.component(viewer, "pickers.search.label")).value(m.query())
                    .placeholder(lang.component(viewer, "pickers.search.placeholder")).maxLength(PickerModel.MAX_QUERY)
                    .search((a, text) -> h.search(a, text)));
            // with no search text yet the list's empty line asks for a name (not "no player found")
            if (m.searchState() == PickerModel.SearchState.SHORT)
                panel.child(Ui.text(lang.component(viewer, "pickers.search.short", "min", m.limits().searchMinChars())).style(Ui.TextStyle.MUTED));
        }
        if (!storeOn && (m.tab() == PlayerSource.RECENT || m.tab() == PlayerSource.SEARCH))
            panel.child(Ui.text(lang.component(viewer, "pickers.store-off")).style(Ui.TextStyle.WARN));
        Ui.ListView list = Ui.list(LIST).empty(emptyText(m, viewer))
                .onSelect((a, key) -> h.row(a, key));
        for (PickerModel.Row r : m.rows(PickerModel.SCREEN_PAGE)) list.item(item(r, viewer, exact, now, zone, m.multiple()));
        panel.child(list);
        int[] span = m.span(PickerModel.SCREEN_PAGE);
        if (span[2] > 0) panel.child(Ui.text(lang.component(viewer, "pickers.rows", "from", span[0], "to", span[1], "total", span[2])).style(Ui.TextStyle.MUTED));
        if (m.multiple()) panel.child(Ui.text(lang.component(viewer, "pickers.chosen-count", "count", m.chosenCount(), "max", m.max())).style(Ui.TextStyle.MUTED));
        int pages = m.pages(PickerModel.SCREEN_PAGE);
        Ui.Button back = Ui.button("back").label(lang.component(viewer, "pickers.back")).ghost().onClick(h::back);
        if (pages > 1) {
            Ui.Button prev = Ui.button("prev").label(lang.component(viewer, "pickers.previous")).disabled(m.page() == 0).onClick(a -> h.page(a, -1));
            Ui.Button next = Ui.button("next").label(lang.component(viewer, "pickers.next")).disabled(m.page() >= pages - 1).onClick(a -> h.page(a, 1));
            if (m.multiple()) panel.footer(back, prev, next, done(flow, viewer, h));
            else panel.footer(back, prev, next);
        } else if (m.multiple()) {
            panel.footer(back, done(flow, viewer, h));
        } else {
            panel.footer(back);
        }
        return panel;
    }

    /** The list's line when it has no rows: on the Search tab without a text, how to search. */
    static Component emptyText(PickerModel m, Player viewer) {
        Lang lang = PickerRuntime.lang();
        if (m.tab() == PlayerSource.SEARCH && m.searchState() == PickerModel.SearchState.EMPTY)
            return lang.component(viewer, "pickers.search.type", "min", m.limits().searchMinChars());
        return lang.component(viewer, "pickers.empty." + m.tab().name().toLowerCase(Locale.ROOT));
    }

    static Ui.Button done(PlayerPickFlow flow, Player viewer, Handlers h) {
        PickerModel m = flow.model;
        Object label = flow.pick.confirmLabel() != null ? PickerRuntime.component(viewer, flow.pick.confirmLabel())
                : PickerRuntime.lang().component(viewer, "pickers.done", "count", m.chosenCount());
        return Ui.button("done").label(label).primary().disabled(m.chosenCount() == 0 && flow.pick.preselected().isEmpty()).onClick(h::done);
    }

    static Ui.ListItem item(PickerModel.Row r, Player viewer, boolean exact, long now, ZoneId zone, boolean multiple) {
        Lang lang = PickerRuntime.lang();
        PickerModel.Candidate c = r.candidate();
        PickerText.Line status = PickerText.status(c, exact, now, zone);
        // a row the rule refuses is greyed out (name and status line), with the reason on the right
        Component subtitle = lang.component(viewer, status.key(), status.args());
        if (!r.allowed()) subtitle = Component.text(PickerRuntime.plain(viewer, subtitle), NamedTextColor.DARK_GRAY);
        Ui.ListItem item = Ui.listItem("p:" + c.id(), r.allowed() ? Component.text(c.name()) : Component.text(c.name(), NamedTextColor.GRAY))
                .subtitle(subtitle)
                .icon(Ui.item("minecraft:player_head", null));
        if (r.chosen()) item.badge(lang.component(viewer, "pickers.row.chosen"));
        if (!r.allowed()) {
            Component reason = Component.text(PickerRuntime.plain(viewer, r.verdict().reason()), NamedTextColor.RED);
            item.right(reason).tooltip(reason);
        } else {
            item.tooltip(lang.component(viewer, !multiple ? "pickers.row.click-pick" : r.chosen() ? "pickers.row.click-untick" : "pickers.row.click-tick"));
        }
        return item;
    }
}
