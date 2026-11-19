package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * A chest menu shown as a settings list (0.66.0): Core's own menus whose slots are settings (the config editor) give
 * {@link InventoryScreens} a {@link Spec} with name, value and description per slot, and the screen shows them with
 * {@link ScreenListLayout} instead of the tile grid. Every row is still its slot: a click on it reaches the menu as the
 * same left or right click on that slot. Slots listed as buttons (Back, Search, page arrows) become a button bar above
 * the hint line. The rows scroll with the mouse wheel. Pure, tested without a server.
 */
public final class InventoryListLayout {
    /** Id of a heading row that has no slot. */
    static final String HEADING = "h";

    private InventoryListLayout() {}

    /** Menus shown as a settings list instead of tiles; weak, so closed menus are forgotten. */
    private static final Map<Object, Spec> LISTS = new java.util.WeakHashMap<>();

    /**
     * Core-internal: shows this menu (an {@code Inventory}) as a settings list when it opens as a screen. Call before
     * opening it. The real chest (fallback, Chest button) is unchanged.
     */
    public static void show(Object inventory, Spec spec) {
        if (inventory != null && spec != null) synchronized (LISTS) { LISTS.put(inventory, spec); }
    }

    /** The list of a menu, or null when it is shown as tiles. */
    public static Spec of(Object inventory) {
        if (inventory == null) return null;
        synchronized (LISTS) { return LISTS.get(inventory); }
    }

    /**
     * One row: {@code slot} of the item it stands for (-1 = a heading without a slot), {@code heading} = separator row,
     * the full {@code name}, the current {@code value} (may be null) and a {@code description} (may be null).
     */
    public record Item(int slot, boolean heading, Component name, Component value, Component description) {}

    /**
     * The list of one menu page: {@code key} names the page (the scroll position is kept while the plugin re-opens a
     * page with the same key, for example after a click changed a value), {@code rows} in order and the slots of
     * {@code buttons} with their labels.
     */
    public record Spec(String key, List<Item> rows, List<Button> buttons) {
        public Spec { rows = List.copyOf(rows); buttons = List.copyOf(buttons); }
    }

    /** A slot shown as a button below the list. */
    public record Button(int slot, Component label) {}

    /** Panel size: as wide as a chest screen may get, up to 2.2 at UI size 1; the height the view allows. */
    static InventoryScreenLayout.Grid frame(ScreenProfile profile) {
        double s = profile.scale();
        double margin = 0.04 * s, head = 0.1 * s, foot = 0.065 * s, gap = 0.008 * s;
        double w = Math.max(0.9 * s, Math.min(profile.viewWidth() * 0.92, 2.2 * s)), h = Math.max(0.6, profile.viewHeight() * 0.86);
        return new InventoryScreenLayout.Grid(s, 9, 1, margin, head, foot, gap, 0.2 * s, w, h, margin, head + gap, 0.034 * s, 0.1 * s, 0.2 * s);
    }

    static List<ScreenListLayout.Row> rows(Spec spec) {
        List<ScreenListLayout.Row> out = new ArrayList<>();
        int headings = 0;
        for (Item item : spec.rows()) {
            String id = item.slot() >= 0 ? InventoryScreenLayout.slotId(item.slot()) : HEADING + headings++;
            out.add(item.heading() ? ScreenListLayout.Row.heading(id, item.name(), item.value(), item.slot() >= 0)
                    : ScreenListLayout.Row.entry(id, null, item.name(), item.value(), item.description(), item.slot() >= 0));
        }
        return out;
    }

    /** Height of the list area. */
    static double listHeight(InventoryScreenLayout.Grid g, Spec spec) {
        double bar = spec.buttons().isEmpty() ? 0 : 0.06 * g.scale() + g.gap();
        return Math.max(0.1, g.height() - g.head() - g.gap() - g.foot() - bar - g.gap());
    }

    static double listWidth(InventoryScreenLayout.Grid g) { return g.width() - 2 * g.margin() - 0.03 * g.scale(); }

    /** The offset clamped to what the list allows. */
    public static int clamp(Spec spec, ScreenProfile profile, int offset) {
        InventoryScreenLayout.Grid g = frame(profile);
        int max = ScreenListLayout.maxOffset(rows(spec), listWidth(g), listHeight(g, spec), new ScreenListLayout.Sizes(g.scale()));
        return Math.max(0, Math.min(max, offset));
    }

    /**
     * @param position text for "{from} to {to} of {total}" (gets the three numbers)
     * @param idleHint hint line when the pointer is on nothing; {@code {position}} is replaced by the position text
     */
    public static InventoryScreenLayout.Result layout(Component title, Spec spec, ScreenProfile profile, int offset, Component closeLabel,
                                                      Component chestLabel, Component chestHint, PositionText position, java.util.function.Function<Component, Component> idleHint) {
        InventoryScreenLayout.Grid g = frame(profile);
        ScreenListLayout.Sizes z = new ScreenListLayout.Sizes(g.scale());
        Map<String, Component> hints = new HashMap<>();
        List<String> ids = chestLabel == null ? List.of(InventoryScreenLayout.CLOSE) : List.of(InventoryScreenLayout.CHEST, InventoryScreenLayout.CLOSE);
        List<Component> labels = chestLabel == null ? List.of(closeLabel) : List.of(chestLabel, closeLabel);
        if (chestLabel != null && chestHint != null) hints.put(InventoryScreenLayout.CHEST, chestHint);
        List<ScreenWidget> out = new ArrayList<>(InventoryScreenLayout.header(g, title, ids, labels));

        List<ScreenListLayout.Row> rows = rows(spec);
        double lw = listWidth(g), lh = listHeight(g, spec), top = g.head() + g.gap();
        int at = Math.max(0, Math.min(ScreenListLayout.maxOffset(rows, lw, lh, z), offset));
        ScreenListLayout.Window window = ScreenListLayout.window(rows, at, lw, lh, z);
        double v = top;
        for (int i = window.from(); i < window.to(); i++) {
            ScreenListLayout.Row row = rows.get(i);
            ScreenListLayout.Placed placed = ScreenListLayout.place(row, g.margin(), v, lw, z);
            out.addAll(placed.widgets());
            v += placed.height();
            Item item = spec.rows().get(i);
            Component hint = item.name().colorIfAbsent(TextColor.color(ScreenListLayout.TEXT));
            if (item.value() != null) hint = hint.append(Component.text(": ").color(TextColor.color(ScreenListLayout.MUTED))).append(item.value());
            if (item.description() != null) hint = hint.append(Component.text(" – ").color(TextColor.color(ScreenListLayout.MUTED)))
                    .append(item.description().colorIfAbsent(TextColor.color(ScreenListLayout.MUTED)));
            hints.put(row.id(), hint);
        }
        if (window.from() > 0 || window.to() < rows.size()) {   // scroll bar
            int max = Math.max(1, ScreenListLayout.maxOffset(rows, lw, lh, z));
            double thumb = Math.max(0.04 * g.scale(), lh * (window.to() - window.from()) / Math.max(1, rows.size()));
            double x = g.width() - g.margin() - 0.008 * g.scale();
            out.add(ScreenWidget.fill("scroll", x, top, 0.008 * g.scale(), lh, 0x60343B47));
            out.add(ScreenWidget.fill("thumb", x, top + (lh - thumb) * Math.min(1, (double) window.from() / max), 0.008 * g.scale(), thumb, 0xD0E3B04B));
        }

        if (!spec.buttons().isEmpty()) {   // the menu's own buttons (Back, Search, page arrows …) in one bar
            double bh = 0.06 * g.scale(), y = g.height() - g.foot() - g.gap() - bh, gap = g.gap();
            double[] widths = new double[spec.buttons().size()];
            double total = 0;
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(0.16 * g.scale(), ScreenListLayout.textW(ScreenListLayout.plain(spec.buttons().get(i).label()), 0.034 * g.scale()) + 0.05 * g.scale());
                total += widths[i] + (i > 0 ? gap : 0);
            }
            double k = total > g.width() - 2 * g.margin() ? (g.width() - 2 * g.margin()) / total : 1;
            double x = (g.width() - total * k) / 2;
            for (int i = 0; i < widths.length; i++) {
                Button b = spec.buttons().get(i);
                String id = InventoryScreenLayout.slotId(b.slot());
                out.add(ScreenWidget.button(id, x, y, widths[i] * k, bh, b.label()).withTextHeight(0.034 * g.scale()));
                hints.put(id, b.label());
                x += (widths[i] + gap) * k;
            }
        }
        int[] pos = ScreenListLayout.position(rows, window);
        Component idle = idleHint.apply(position.of(pos[0], pos[1], pos[2]));
        out.addAll(InventoryScreenLayout.footer(g, idle));
        return new InventoryScreenLayout.Result(new ScreenLayout(g.width(), g.height(), out), hints, idle);
    }

    /** The "3 to 12 of 27" text. */
    @FunctionalInterface
    public interface PositionText { Component of(int from, int to, int total); }
}
