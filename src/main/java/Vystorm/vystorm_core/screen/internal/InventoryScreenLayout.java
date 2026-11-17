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
    static final int TEXT = 0xF2F2F2, MUTED = 0x9AA3B2, LINE = 0xFF343B47, TILE = 0x70252A33, TILE_LIT = 0xF03A4352, EMPTY = 0x30252A33;
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
        double s = profile.scale();
        int cols = Math.max(1, columns);
        int rows = Math.max(1, (slots.size() + cols - 1) / cols);
        double margin = 0.04 * s, head = 0.1 * s, foot = 0.065 * s, gap = 0.008 * s;
        double maxW = profile.viewWidth() * 0.92, maxH = profile.viewHeight() * 0.86;
        double tile = Math.min(0.2 * s, Math.min((maxW - 2 * margin) / cols, (maxH - head - foot - 2 * gap) / rows));
        double w = Math.max(0.9 * s, cols * tile + 2 * margin), h = head + gap + rows * tile + gap + foot;
        double gridX = (w - cols * tile) / 2, gridY = head + gap;

        List<ScreenWidget> out = new ArrayList<>();
        Map<String, Component> hints = new HashMap<>();
        double closeW = 0.2 * s;
        double buttons = chestLabel == null ? closeW : 2 * closeW + gap;
        out.add(ScreenWidget.label("title", margin, 0.025 * s, w - 2 * margin - buttons - gap, 0.06 * s,
                title.colorIfAbsent(TextColor.color(TEXT)).decorate(TextDecoration.BOLD)).withAlign(ScreenWidget.Align.START));
        if (chestLabel != null) {
            out.add(ScreenWidget.button(CHEST, w - margin - 2 * closeW - gap, 0.025 * s, closeW, 0.06 * s, chestLabel).withTextHeight(0.034 * s));
            if (chestHint != null) hints.put(CHEST, chestHint);
        }
        out.add(ScreenWidget.button(CLOSE, w - margin - closeW, 0.025 * s, closeW, 0.06 * s, closeLabel).withTextHeight(0.034 * s));
        out.add(ScreenWidget.fill("head-line", 0, head, w, 0.004 * s, LINE));

        double nameH = Math.max(0.018, tile * 0.16), icon = tile * 0.52;
        for (Slot slot : slots) {
            int r = slot.index() / cols, c = slot.index() % cols;
            if (r >= rows) continue;
            double x = gridX + c * tile, y = gridY + r * tile, inner = tile - gap;
            if (slot.empty()) {
                out.add(ScreenWidget.fill("e" + slot.index(), x + gap / 2, y + gap / 2, inner, inner, EMPTY));
                continue;
            }
            String id = slotId(slot.index());
            out.add(new ScreenWidget(id, ScreenWidget.Kind.BUTTON, x + gap / 2, y + gap / 2, inner, inner, "", true)
                    .withFill(TILE).withHighlight(TILE_LIT));
            out.add(ScreenWidget.icon("i" + slot.index(), x + (tile - icon) / 2, y + tile * 0.1, icon, icon, slot.icon()));
            if (slot.amount() > 1) {
                out.add(ScreenWidget.label("n" + slot.index(), x + tile * 0.5, y + tile * 0.1 + icon - nameH, tile * 0.42, nameH,
                        Component.text(Integer.toString(slot.amount())).color(TextColor.color(TEXT))).withAlign(ScreenWidget.Align.END));
            }
            out.add(ScreenWidget.label("t" + slot.index(), x + gap, y + tile * 0.1 + icon + tile * 0.04, tile - 2 * gap, nameH,
                    shorten(slot.name(), 14)));
            hints.put(id, hint(slot));
        }

        out.add(ScreenWidget.fill("foot-line", 0, h - foot, w, 0.004 * s, LINE));
        out.add(ScreenWidget.label(HINT, margin, h - foot + 0.016 * s, w - 2 * margin, 0.034 * s, idleHint).withAlign(ScreenWidget.Align.START));
        return new Result(new ScreenLayout(w, h, out), hints, idleHint);
    }

    /** Full name plus the first lore lines, joined into one line. */
    static Component hint(Slot slot) {
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
