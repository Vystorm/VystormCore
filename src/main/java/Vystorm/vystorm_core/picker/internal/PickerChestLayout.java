package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PlayerSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The 54-slot chest of the player picker (design 3.5), as a plain slot map without items: rows 1 to 5 (slots 0 to 44)
 * one head per player (a ticked one becomes a lime block with a green check before the name), row 6: 45 Back, 46 Online, 47 Nearby, 48 Recent, 49 Search, 50 Info, 51 Done (multiple only),
 * 52 Previous, 53 Next. The active tab glints; tab items of empty sources are left out (the search item and the
 * current tab stay); with only one source no tab item is drawn except the search item of a search-only pick.
 * Pure. Core-internal.
 */
public final class PickerChestLayout {
    public static final int SIZE = 54;
    public static final int BACK = 45, INFO = 50, DONE = 51, PREVIOUS = 52, NEXT = 53, SEARCH = 49;

    /** {@code CHOSEN}: a ticked row (0.71.0: heads draw no enchantment glint, so a ticked head looked unticked). */
    public enum Icon { HEAD, CHOSEN, BARRIER, CLOCK, COMPASS, BOOK, NAME_TAG, PAPER, LIME_DYE, ARROW }

    public enum Action { NONE, ROW, BACK, TAB, SEARCH, DONE, PREVIOUS, NEXT }

    /** One filled slot. {@code row} for heads, {@code tab} for tab items; {@code name} is a key line, or a raw text. */
    public record Slot(int slot, Icon icon, PickerText.Line name, List<PickerText.Line> lore, boolean glint, Action action,
                       PlayerSource tab, PickerModel.Row row) {
        public Slot { lore = List.copyOf(lore); }
    }

    /** What the chest shows besides the model: exact dates, the time, an error line of the last hand-over, the Done label. */
    public record Context(boolean exactLastSeen, long now, ZoneId zone, Object error, Object confirmLabel) {}

    private PickerChestLayout() {}

    public static int tabSlot(PlayerSource s) {
        return switch (s) {
            case ONLINE -> 46;
            case NEARBY -> 47;
            case RECENT -> 48;
            case SEARCH -> SEARCH;
        };
    }

    static Icon tabIcon(PlayerSource s) {
        return switch (s) {
            case ONLINE -> Icon.CLOCK;
            case NEARBY -> Icon.COMPASS;
            case RECENT -> Icon.BOOK;
            case SEARCH -> Icon.NAME_TAG;
        };
    }

    public static List<Slot> layout(PickerModel model, Context ctx) {
        List<Slot> out = new ArrayList<>();
        int slot = 0;
        for (PickerModel.Row row : model.rows(PickerModel.CHEST_PAGE)) out.add(head(slot++, row, model.multiple(), ctx));
        out.add(new Slot(BACK, Icon.BARRIER, PickerText.Line.of("pickers.back"), List.of(PickerText.Line.of("pickers.chest.back-hint")), false, Action.BACK, null, null));
        List<PlayerSource> tabs = model.visibleTabs();
        for (PlayerSource s : model.sources()) {
            boolean searchOnly = s == PlayerSource.SEARCH && model.sources().size() == 1;
            if (!tabs.contains(s) && !searchOnly) continue;
            boolean current = s == model.tab();
            if (s == PlayerSource.SEARCH) {
                List<PickerText.Line> lore = new ArrayList<>();
                lore.add(model.query().isEmpty() ? PickerText.Line.of("pickers.chest.search-none") : PickerText.Line.of("pickers.chest.search-current", "query", model.query()));
                lore.add(PickerText.Line.of("pickers.chest.search-hint"));
                out.add(new Slot(SEARCH, Icon.NAME_TAG, PickerText.Line.of("pickers.tab.search"), lore, current, Action.SEARCH, s, null));
            } else {
                out.add(new Slot(tabSlot(s), tabIcon(s), PickerText.Line.of(PickerText.tabKey(s), "count", model.count(s)),
                        List.of(PickerText.Line.of(current ? "pickers.chest.tab-current" : "pickers.chest.tab-hint")), current, Action.TAB, s, null));
            }
        }
        out.add(new Slot(INFO, Icon.PAPER, PickerText.Line.of("pickers.chest.info"), info(model, ctx), false, Action.NONE, null, null));
        if (model.multiple()) {
            PickerText.Line label = ctx.confirmLabel() != null ? PickerText.Line.raw(ctx.confirmLabel()) : PickerText.Line.of("pickers.chest.done", "count", model.chosenCount());   // short: a mirrored screen shows 14 characters
            out.add(new Slot(DONE, Icon.LIME_DYE, label, List.of(PickerText.Line.of("pickers.chest.done-hint", "count", model.chosenCount(), "max", model.max())),
                    false, Action.DONE, null, null));
        }
        int pages = model.pages(PickerModel.CHEST_PAGE);
        if (model.page() > 0)
            out.add(new Slot(PREVIOUS, Icon.ARROW, PickerText.Line.of("pickers.previous"), List.of(PickerText.Line.of("pickers.chest.page", "page", model.page(), "pages", pages)), false, Action.PREVIOUS, null, null));
        if (model.page() < pages - 1)
            out.add(new Slot(NEXT, Icon.ARROW, PickerText.Line.of("pickers.next"), List.of(PickerText.Line.of("pickers.chest.page", "page", model.page() + 2, "pages", pages)), false, Action.NEXT, null, null));
        return out;
    }

    static Slot head(int slot, PickerModel.Row row, boolean multiple, Context ctx) {
        PickerModel.Candidate c = row.candidate();
        List<PickerText.Line> lore = new ArrayList<>();
        if (row.chosen()) lore.add(PickerText.Line.of("pickers.row.chosen"));
        lore.add(PickerText.status(c, ctx.exactLastSeen(), ctx.now(), ctx.zone()));
        if (!row.allowed()) lore.add(PickerText.Line.of("pickers.row.denied", "reason", row.verdict().reason()));
        else if (!multiple) lore.add(PickerText.Line.of("pickers.row.click-pick"));
        else lore.add(PickerText.Line.of(row.chosen() ? "pickers.row.click-untick" : "pickers.row.click-tick"));
        // a ticked row is a lime block named "✔ name" in green: a head shows no glint, so it would look unticked
        if (row.chosen()) return new Slot(slot, Icon.CHOSEN, PickerText.Line.of("pickers.row.name-chosen", "player", c.name()), lore, true, Action.ROW, null, row);
        return new Slot(slot, Icon.HEAD, PickerText.Line.of("pickers.row.name", "player", c.name()), lore, false, Action.ROW, null, row);
    }

    static List<PickerText.Line> info(PickerModel model, Context ctx) {
        List<PickerText.Line> lore = new ArrayList<>();
        if (model.multiple()) lore.add(PickerText.Line.of("pickers.chosen-count", "count", model.chosenCount(), "max", model.max()));
        int[] span = model.span(PickerModel.CHEST_PAGE);
        if (span[2] > 0) lore.add(PickerText.Line.of("pickers.rows", "from", span[0], "to", span[1], "total", span[2]));
        lore.add(PickerText.Line.of("pickers.chest.page", "page", model.page() + 1, "pages", model.pages(PickerModel.CHEST_PAGE)));
        if (model.tab() == PlayerSource.SEARCH) {
            switch (model.searchState()) {
                case EMPTY -> lore.add(PickerText.Line.of("pickers.search.type", "min", model.limits().searchMinChars()));
                case SHORT -> lore.add(PickerText.Line.of("pickers.search.short", "min", model.limits().searchMinChars()));
                case OK -> { }
            }
        }
        if (span[2] == 0 && !(model.tab() == PlayerSource.SEARCH && model.searchState() == PickerModel.SearchState.EMPTY))
            lore.add(PickerText.Line.of("pickers.empty." + model.tab().name().toLowerCase(java.util.Locale.ROOT)));
        if (ctx.error() != null) lore.add(PickerText.Line.of("pickers.error", "reason", ctx.error()));
        return lore;
    }
}
