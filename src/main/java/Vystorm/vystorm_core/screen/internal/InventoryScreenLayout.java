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
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Layout of a chest menu shown as a screen (since 0.58.0): the slots as a grid of tiles (icon, count, short name) in the
 * Vystorm look, title and close button on top, a hint line at the bottom that shows the full name and lore of the tile
 * under the pointer. Filler panes (glass panes without a name) stay empty. Pure, tested without a server.
 */
public final class InventoryScreenLayout {
    public static final int TEXT = 0xF2F2F2, MUTED = 0x9AA3B2, LINE = 0xFF343B47, TILE = 0x70252A33, TILE_LIT = 0xF03A4352, EMPTY = 0x30252A33;
    public static final String CLOSE = "close", HINT = "hint", SLOT = "s";
    /** Button that shows the menu as the real chest (0.61.0), for moving items and shift-clicks. */
    public static final String CHEST = "chest";

    /** One slot as the layout sees it; {@code icon} is the ItemStack (or Material), null = empty slot. */
    public record Slot(int index, Object icon, Component name, List<Component> lore, int amount, boolean filler) {
        public Slot { lore = lore == null ? List.of() : List.copyOf(lore); }
        public boolean empty() { return icon == null || filler; }
    }

    public record Result(ScreenLayout layout, Map<String, Component> hints, Component hint) {}

    private InventoryScreenLayout() {}

    /** Widget id of a slot tile. */
    public static String slotId(int index) { return SLOT + index; }

    /** Slot of a widget id, or -1. */
    public static int slotOf(String widgetId) {
        if (widgetId == null || !widgetId.startsWith(SLOT)) return -1;
        try { return Integer.parseInt(widgetId.substring(SLOT.length())); } catch (NumberFormatException no) { return -1; }
    }

    /**
     * @param columns 9 for chests, 5 for hoppers, 3 for dispensers
     * @param closeLabel text of the close button
     * @param idleHint hint line when the pointer is on nothing
     */
    public static Result layout(Component title, List<Slot> slots, int columns, ScreenProfile profile, Component closeLabel, Component idleHint) {
        return layout(title, slots, columns, profile, closeLabel, idleHint, null, null);
    }

    /**
     * {@link #layout(Component, List, int, ScreenProfile, Component, Component)} with a button left of Close that opens
     * the real chest ({@code chestLabel}, null = no button); {@code chestHint} is shown while the pointer is on it.
     */
    public static Result layout(Component title, List<Slot> slots, int columns, ScreenProfile profile, Component closeLabel, Component idleHint,
                                Component chestLabel, Component chestHint) {
        Grid grid = Grid.of(slots.size(), columns, profile);
        Map<String, Component> hints = new HashMap<>();
        List<String> ids = chestLabel == null ? List.of(CLOSE) : List.of(CHEST, CLOSE);
        List<Component> labels = chestLabel == null ? List.of(closeLabel) : List.of(chestLabel, closeLabel);
        if (chestLabel != null && chestHint != null) hints.put(CHEST, chestHint);
        List<ScreenWidget> out = new ArrayList<>(header(grid, title, ids, labels));
        for (Slot slot : slots) {
            if (slot.index() / grid.columns() >= grid.rows()) continue;
            if (slot.empty()) {
                out.add(empty(grid, "e" + slot.index(), slot.index()));
                continue;
            }
            String id = slotId(slot.index());
            out.addAll(tile(grid, id, "i" + slot.index(), "n" + slot.index(), "t" + slot.index(), slot));
            hints.put(id, hint(slot));
        }
        out.addAll(footer(grid, idleHint));
        return new Result(new ScreenLayout(grid.width(), grid.height(), out), hints, idleHint);
    }

    /** Characters of a slot name on its tile. */
    public static final int NAME_CHARS = 14;
    /** Widget ids of the frame besides {@link #CLOSE}, {@link #CHEST} and {@link #HINT}. */
    public static final String TITLE = "title", HEAD_LINE = "head-line", FOOT_LINE = "foot-line";

    /**
     * Sizes of the tile grid for one display (0.65.0, shared with file menus): {@code slots} slots in {@code columns}
     * columns; the tile is at most 0.2 at UI size 1 and shrinks until the panel fits 92 % x 86 % of the view.
     */
    public record Grid(double scale, int columns, int rows, double margin, double head, double foot, double gap, double tile,
                       double width, double height, double gridX, double gridY, double nameHeight, double icon, double buttonWidth) {
        public static Grid of(int slots, int columns, ScreenProfile profile) {
            double s = profile.scale();
            int cols = Math.max(1, columns);
            int rows = Math.max(1, (slots + cols - 1) / cols);
            double margin = 0.04 * s, head = 0.1 * s, foot = 0.065 * s, gap = 0.008 * s;
            double maxW = profile.viewWidth() * 0.92, maxH = profile.viewHeight() * 0.86;
            double tile = Math.min(0.2 * s, Math.min((maxW - 2 * margin) / cols, (maxH - head - foot - 2 * gap) / rows));
            double w = Math.max(0.9 * s, cols * tile + 2 * margin), h = head + gap + rows * tile + gap + foot;
            return new Grid(s, cols, rows, margin, head, foot, gap, tile, w, h, (w - cols * tile) / 2, head + gap,
                    Math.max(0.018, tile * 0.16), tile * 0.52, 0.2 * s);
        }
        /** Left edge of the cell of slot {@code index}. */
        public double cellU(int index) { return gridX + (index % columns) * tile; }
        /** Top edge of the cell of slot {@code index}. */
        public double cellV(int index) { return gridY + (index / columns) * tile; }
        public int slots() { return columns * rows; }
    }

    /**
     * Title (bold, left), the header buttons right-aligned in the order given, and the line under the header. Chest
     * menus have Close and sometimes the chest button; the same numbers hold for any count.
     */
    public static List<ScreenWidget> header(Grid g, Component title, List<String> ids, List<? extends Component> labels) {
        double s = g.scale(), w = g.width();
        int n = ids.size();
        double buttons = n * g.buttonWidth() + Math.max(0, n - 1) * g.gap();
        List<ScreenWidget> out = new ArrayList<>();
        out.add(ScreenWidget.label(TITLE, g.margin(), 0.025 * s, w - 2 * g.margin() - buttons - g.gap(), 0.06 * s,
                title.colorIfAbsent(TextColor.color(TEXT)).decorate(TextDecoration.BOLD)).withAlign(ScreenWidget.Align.START));
        for (int i = 0; i < n; i++)
            out.add(ScreenWidget.button(ids.get(i), w - g.margin() - (n - i) * g.buttonWidth() - (n - i - 1) * g.gap(), 0.025 * s, g.buttonWidth(), 0.06 * s,
                    labels.get(i)).withTextHeight(0.034 * s));
        out.add(ScreenWidget.fill(HEAD_LINE, 0, g.head(), w, 0.004 * s, LINE));
        return out;
    }

    /** The line above the hint and the hint label. */
    public static List<ScreenWidget> footer(Grid g, Component idleHint) {
        double s = g.scale();
        return List.of(ScreenWidget.fill(FOOT_LINE, 0, g.height() - g.foot(), g.width(), 0.004 * s, LINE),
                ScreenWidget.label(HINT, g.margin(), g.height() - g.foot() + 0.016 * s, g.width() - 2 * g.margin(), 0.034 * s, idleHint).withAlign(ScreenWidget.Align.START));
    }

    /** The dark cell of an empty slot. */
    public static ScreenWidget empty(Grid g, String id, int index) {
        double inner = g.tile() - g.gap();
        return ScreenWidget.fill(id, g.cellU(index) + g.gap() / 2, g.cellV(index) + g.gap() / 2, inner, inner, EMPTY);
    }

    /**
     * A filled slot: the clickable box {@code id}, the icon, the amount (only above 1) and the name cut to
     * {@link #NAME_CHARS} characters.
     */
    public static List<ScreenWidget> tile(Grid g, String id, String iconId, String amountId, String nameId, Slot slot) {
        double x = g.cellU(slot.index()), y = g.cellV(slot.index()), tile = g.tile(), gap = g.gap(), inner = tile - gap, icon = g.icon(), nameH = g.nameHeight();
        List<ScreenWidget> out = new ArrayList<>();
        out.add(new ScreenWidget(id, ScreenWidget.Kind.BUTTON, x + gap / 2, y + gap / 2, inner, inner, "", true)
                .withFill(TILE).withHighlight(TILE_LIT));
        out.add(ScreenWidget.icon(iconId, x + (tile - icon) / 2, y + tile * 0.1, icon, icon, slot.icon()));
        if (slot.amount() > 1) {
            out.add(ScreenWidget.label(amountId, x + tile * 0.5, y + tile * 0.1 + icon - nameH, tile * 0.42, nameH,
                    Component.text(Integer.toString(slot.amount())).color(TextColor.color(TEXT))).withAlign(ScreenWidget.Align.END));
        }
        out.add(ScreenWidget.label(nameId, x + gap, y + tile * 0.1 + icon + tile * 0.04, tile - 2 * gap, nameH, shorten(slot.name(), NAME_CHARS)));
        return out;
    }

    /** Full name plus the first lore lines, joined into one line. */
    public static Component hint(Slot slot) {
        Component out = slot.name().colorIfAbsent(TextColor.color(TEXT));
        int shown = 0;
        for (Component line : slot.lore()) {
            String plain = plain(line).strip();
            if (plain.isEmpty()) continue;
            if (shown++ >= 3) { out = out.append(Component.text(" …").color(TextColor.color(MUTED))); break; }
            out = out.append(Component.text("  ·  ").color(TextColor.color(MUTED))).append(line.colorIfAbsent(TextColor.color(MUTED)));
        }
        return out;
    }

    /** Plain name cut to {@code max} characters, keeping the first colour of the name. */
    static Component shorten(Component name, int max) {
        if (name instanceof net.kyori.adventure.text.TranslatableComponent) return name.colorIfAbsent(TextColor.color(TEXT));   // client translates
        String plain = plain(name).strip();
        String cut = plain.length() <= max ? plain : plain.substring(0, max - 1) + "…";
        TextColor color = name.color();
        if (color == null) for (Component child : name.children()) if (child.color() != null) { color = child.color(); break; }
        return Component.text(cut).color(color == null ? TextColor.color(TEXT) : color);
    }

    static String plain(Component c) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c); }
}
