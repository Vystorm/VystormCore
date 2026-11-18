package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.autoGui.MenuEntryView;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The Vystorm main menu ({@code /menu}) as a screen (since 0.57.0): the same entries, categories, permissions and
 * actions as the menu panel, shown as icon tiles. Size and column count follow the player's display settings
 * ({@link ScreenProfile}, {@code /screen settings}). The mouse wheel scrolls, the footer shows the hovered entry's
 * description; a tile closes the screen and opens its menu.
 */
public final class MenuScreen {
    public static final double MARGIN = 0.05;
    public static final int LABEL_CHARS = 18;

    /** Panel size and grid for one display profile; every length scales with the UI size. */
    record Metrics(double width, double height, int columns, double scale) {
        static Metrics of(ScreenProfile profile) {
            double s = profile.scale();
            double width = Math.max(1.0, Math.min(4.5, profile.viewWidth() * 0.82));
            double height = Math.max(0.7, Math.min(1.6, profile.viewHeight() * 0.80));
            int columns = Math.max(3, Math.min(12, (int) Math.floor((width - 2 * MARGIN) / (0.2 * s))));
            return new Metrics(width, height, columns, s);
        }
        double top() { return 0.15 * scale; }
        double footer() { return 0.09 * scale; }
        double headerRow() { return 0.07 * scale; }
        double tileRow() { return 0.17 * scale; }
        double tileIcon() { return 0.085 * scale; }
        /** Tiles keep their size; rows are centred on the panel (owner, 2026-11-15). */
        double tileWidth() { return 0.2 * scale; }
        double tileLabel() { return 0.04 * scale; }
    }

    /** One row of the scrolling content: a section title or up to {@code columns} entries. */
    record Row(Component title, List<MenuEntryView> entries) {
        double height(Metrics m) { return title != null ? m.headerRow() : m.tileRow(); }
    }

    private final Plugin core;
    private final GuiInteface gui;
    private final Lang lang;

    public MenuScreen(Plugin core, GuiInteface gui) {
        this.core = core;
        this.gui = gui;
        this.lang = Localization.get().core();
    }

    /** Opens the menu screen; {@code classic} runs when no screen can be shown. */
    public ScreenOpenResult open(Player player, Consumer<Player> classic) {
        Metrics metrics = Metrics.of(Screens.of(core).profile(player));
        List<Row> rows = rows(player, metrics.columns());
        int[] offset = { 0 };
        List<MenuEntryView> entries = rows.stream().flatMap(r -> r.entries().stream()).toList();
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:menu", layout(player, rows, metrics, 0, null))
                .backdrop(ScreenSpec.Backdrop.BLACK)
                .onInput((session, input) -> handle(session, input, rows, entries, metrics, offset, classic))
                .fallback(classic)
                .build();
        return ScreenRuntime.get().open(core, player, spec, new ScreenRuntime.Origin("vystorm_core:menu", p -> open(p, classic), true));
    }

    /** Section rows in menu order: entries outside categories first, then every category with entries. */
    List<Row> rows(Player player, int columns) {
        List<Row> rows = new ArrayList<>();
        List<MenuEntryView> root = gui.menuEntries(player, "").stream().filter(e -> !isCategory(e.key())).map(e -> resolved(player, e)).toList();
        if (!root.isEmpty()) addSection(rows, lang.component(player, "menu.panel.general"), root, columns);
        for (MenuCategory category : MenuCategory.ordered()) {
            List<MenuEntryView> entries = gui.menuEntries(player, category.group()).stream().map(e -> resolved(player, e)).toList();
            if (!entries.isEmpty()) addSection(rows, lang.component(player, category.langKey() + ".title"), entries, columns);
        }
        return rows;
    }

    /** Menu titles and descriptions are lang references ({@code {lang:...}}); show them in the viewer's language. */
    private static MenuEntryView resolved(Player player, MenuEntryView e) {
        var lang = Localization.get();
        return new MenuEntryView(e.key(), lang.resolve(player, e.title()), e.icon(), e.group(), e.description() == null ? "" : lang.resolve(player, e.description()));
    }

    static void addSection(List<Row> rows, Component title, List<MenuEntryView> entries, int columns) {
        rows.add(new Row(title, List.of()));
        for (int i = 0; i < entries.size(); i += columns) rows.add(new Row(null, List.copyOf(entries.subList(i, Math.min(entries.size(), i + columns)))));
    }

    static boolean isCategory(String key) { return key.startsWith("vystorm_core:category-") || key.equals("vystorm_core:tutorials"); }

    /** How many rows starting at {@code offset} fit into the content area. */
    static int visibleRows(List<Row> rows, int offset, Metrics m) {
        double room = m.height() - m.top() - m.footer(), used = 0;
        int count = 0;
        for (int i = offset; i < rows.size(); i++) {
            if (used + rows.get(i).height(m) > room + 1e-9) break;
            used += rows.get(i).height(m);
            count++;
        }
        return count;
    }

    /** Largest scroll offset that still fills the content area as far as possible. */
    static int maxOffset(List<Row> rows, Metrics m) {
        for (int offset = 0; offset < rows.size(); offset++) if (offset + visibleRows(rows, offset, m) >= rows.size()) return offset;
        return 0;
    }

    /** Fill of a tile box. */
    public static final int TILE_FILL = 0x50252A33;
    /** Widget ids of the frame: title label, footer label and the three header buttons. */
    public static final String TITLE = "title", FOOTER = "footer", DISPLAY = "display", SEARCH = "search", CLOSE = "close";

    /**
     * The header of a menu-style panel of width {@code w} at UI size {@code s} (0.65.0, shared with file menus): the
     * title label and the header buttons, right-aligned at the margin. The main menu has three buttons; the same
     * numbers hold for any count. {@code ids} and {@code labels} run from left to right.
     */
    public static List<ScreenWidget> header(double w, double s, Object title, List<String> ids, List<?> labels) {
        double button = 0.22 * s, gap = 0.02 * s, head = 0.07 * s;
        int n = ids.size();
        List<ScreenWidget> out = new ArrayList<>();
        out.add(ScreenWidget.label(TITLE, MARGIN, 0.03 * s, w - 2 * MARGIN - n * (button + gap), 0.08 * s, title));
        for (int i = 0; i < n; i++)
            out.add(ScreenWidget.button(ids.get(i), w - MARGIN - (n - i) * button - (n - i - 1) * gap, 0.03 * s, button, head, labels.get(i)));
        return out;
    }

    /** The footer label of a menu-style panel of {@code w} x {@code h} at UI size {@code s} (0.65.0, shared with file menus). */
    public static ScreenWidget footer(double w, double h, double s, Object text) {
        return ScreenWidget.label(FOOTER, MARGIN, h - 0.09 * s + 0.02 * s, w - 2 * MARGIN, 0.045 * s, text);
    }

    /**
     * One tile (0.65.0, shared with file menus): the box {@code (u, v, width, height)} with the caption cut to
     * {@link #LABEL_CHARS} characters at its bottom edge, and on top of it the icon, {@code iconSize} square, centred
     * in the box and starting at {@code iconV}. Returns the box, then the icon.
     */
    public static List<ScreenWidget> tile(String id, String iconId, double u, double v, double width, double height, double iconSize, double iconV,
                                          String caption, Object icon, double labelHeight) {
        return List.of(
                ScreenWidget.button(id, u, v, width, height, Component.text(shorten(caption))).withTextHeight(labelHeight).withFill(TILE_FILL),
                ScreenWidget.icon(iconId, u + (width - iconSize) / 2, iconV, iconSize, iconSize, icon));
    }

    ScreenLayout layout(Player player, List<Row> rows, Metrics m, int offset, Component footer) {
        double s = m.scale(), w = m.width();
        List<ScreenWidget> widgets = new ArrayList<>(header(w, s, lang.component(player, "menu.directory.title"), List.of(DISPLAY, SEARCH, CLOSE),
                List.of(lang.component(player, "menu.screen.display"), lang.component(player, "menu.screen.search"), lang.component(player, "menu.screen.close"))));
        double v = m.top(), tileWidth = m.tileWidth(), icon = m.tileIcon();
        int shown = visibleRows(rows, offset, m);
        int index = rows.stream().limit(offset).mapToInt(r -> r.entries().size()).sum();
        for (int r = offset; r < offset + shown; r++) {
            Row row = rows.get(r);
            if (row.title() != null) {
                widgets.add(ScreenWidget.label("h" + r, MARGIN, v + 0.01 * s, w - 2 * MARGIN, 0.05 * s, row.title()));
            } else {
                for (int c = 0; c < row.entries().size(); c++) {
                    MenuEntryView entry = row.entries().get(c);
                    double u = (w - row.entries().size() * tileWidth) / 2 + c * tileWidth;
                    // tile box first, icon on top of it
                    widgets.addAll(tile("t" + index, "i" + index, u + 0.005, v + 0.005, tileWidth - 0.01, m.tileRow() - 0.01, icon, v + 0.01 * s,
                            entry.title(), entry.icon() == null || entry.icon().isAir() ? Material.PAPER : entry.icon(), m.tileLabel()));
                    index++;
                }
            }
            v += row.height(m);
        }
        Component hint = footer != null ? footer : rows.isEmpty() ? lang.component(player, "menu.directory.empty") : hint(player, rows, m, offset);
        widgets.add(footer(w, m.height(), s, hint));
        return new ScreenLayout(w, m.height(), widgets);
    }

    private Component hint(Player player, List<Row> rows, Metrics m, int offset) {
        return lang.component(player, "menu.screen.hint", "page", offset + 1, "pages", Math.max(1, maxOffset(rows, m) + 1));
    }

    public static String shorten(String title) {
        String plain = title == null ? "" : title.replaceAll("§.", "");
        return plain.length() <= LABEL_CHARS ? plain : plain.substring(0, LABEL_CHARS - 1) + "…";
    }

    private void handle(ScreenSession session, ScreenInput input, List<Row> rows, List<MenuEntryView> entries, Metrics m, int[] offset,
                        Consumer<Player> classic) {
        Player player = session.player();
        switch (input) {
            case ScreenInput.Scroll scroll -> {
                int next = Math.max(0, Math.min(maxOffset(rows, m), offset[0] + scroll.delta()));
                if (next == offset[0]) return;
                offset[0] = next;
                session.setLayout(layout(player, rows, m, next, null));
            }
            case ScreenInput.Hover hover -> {
                MenuEntryView entry = entry(hover.widgetId(), entries);
                Component info = entry == null ? hint(player, rows, m, offset[0])
                        : Component.text(entry.title() + (entry.description().isBlank() ? "" : " – " + entry.description()));
                session.setText("footer", info);
            }
            case ScreenInput.Click click when "close".equals(click.widgetId()) -> session.close();
            case ScreenInput.Click click when "display".equals(click.widgetId()) -> {
                session.close();
                new ScreenSettings(core).open(player, p -> open(p, classic));
            }
            case ScreenInput.Click click when "search".equals(click.widgetId()) -> {
                session.close();
                Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.open(player, gui);
            }
            case ScreenInput.Click click -> {
                MenuEntryView entry = entry(click.widgetId(), entries);
                if (entry == null) return;
                session.close();
                if (!gui.openMenu(player, entry.key())) lang.send(player, "common.menu-unavailable");
            }
            default -> { }
        }
    }

    /** Entry for a tile id {@code t<index>}, or null. */
    static MenuEntryView entry(String widgetId, List<MenuEntryView> entries) {
        if (widgetId == null || !widgetId.startsWith("t")) return null;
        try {
            int index = Integer.parseInt(widgetId.substring(1));
            return index >= 0 && index < entries.size() ? entries.get(index) : null;
        } catch (NumberFormatException notATile) {
            return null;
        }
    }
}
