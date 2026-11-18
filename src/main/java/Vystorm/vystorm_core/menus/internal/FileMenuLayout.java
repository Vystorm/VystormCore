package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.internal.InventoryScreenLayout;
import Vystorm.vystorm_core.screen.internal.MenuScreen;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;

/**
 * Builds the screen of a file menu for one viewer: the frame with the numbers of Core's own screens
 * ({@link MenuScreen#header}, {@link MenuScreen#tile}, {@link InventoryScreenLayout.Grid}), then the widgets of the
 * file that this viewer sees on this page, every length multiplied by the UI size. A panel that does not fit the
 * viewer's display is shrunk by one factor, never laid out anew. The editor computes the same positions with its
 * JavaScript port of these classes ({@code FileMenuLayoutTest} compares them). Pure: no server needed.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FileMenuLayout {
    /** Icon and caption of a tile outside a chest grid at UI size 1 (the tiles of the main menu). */
    static final double TILE_ICON = 0.085, TILE_LABEL = 0.04, TILE_ICON_DROP = 0.005;
    /** A fill without a colour: the line colour of Core's screens. */
    static final int FILL_DEFAULT = 0xFF343B47;
    /** Empty cells of a chest grid get ids no file can use (file ids have no colon). */
    static final String EMPTY_PREFIX = "vystorm:empty";

    /**
     * What this viewer sees. {@code allowed} answers a permission as the file writes it ({@code node} or
     * {@code !node}); {@code icon} turns a material name into what {@link ScreenWidget#icon} takes; {@code labels}
     * are the texts of the header buttons by id.
     */
    public record View(ScreenProfile profile, int page, Predicate<String> allowed, MenuText text, Function<String, Object> icon,
                       Map<String, Component> labels) {}

    /**
     * @param hints footer text while the pointer is on a widget, by widget id
     * @param idle footer text otherwise
     * @param footerId id of the footer label, null when the frame has none
     * @param shrink factor the panel was shrunk by to fit the display (1 = as designed)
     */
    public record Built(ScreenLayout layout, Map<String, Component> hints, Component idle, String footerId, double shrink, int page) {
        /** The footer text while the pointer is on {@code widgetId} (null = on nothing). */
        public Component footer(String widgetId) {
            Component hint = widgetId == null ? null : hints.get(widgetId);
            return hint == null ? idle : hint;
        }
    }

    private FileMenuLayout() {}

    public static Built build(MenuFile menu, View view) {
        ScreenProfile profile = view.profile() == null ? ScreenProfile.DEFAULT : view.profile();
        double s = profile.scale();
        int page = Math.max(1, Math.min(menu.pages(), view.page()));
        MenuText text = view.text();
        List<ScreenWidget> out = new ArrayList<>(), tail = new ArrayList<>();
        Map<String, Component> hints = new HashMap<>();
        List<String> buttons = menu.frame() == MenuFile.Frame.NONE ? List.of() : menu.header();
        List<Component> labels = new ArrayList<>();
        for (String id : buttons) labels.add(view.labels().getOrDefault(id, Component.text(id)));
        Component idle = text.component(menu.footer());
        String footerId = null;
        double width, height;
        InventoryScreenLayout.Grid grid = null;

        if (menu.frame() == MenuFile.Frame.CHEST) {
            grid = InventoryScreenLayout.Grid.of(menu.columns() * menu.rows(), menu.columns(), profile);
            width = grid.width();
            height = grid.height();
            footerId = InventoryScreenLayout.HINT;
            out.addAll(InventoryScreenLayout.header(grid, text.component(menu.title()), buttons, labels));
            tail.addAll(InventoryScreenLayout.footer(grid, idle));
        } else {
            width = menu.width() * s;
            height = menu.height() * s;
            if (menu.frame() == MenuFile.Frame.MENU) {
                footerId = MenuScreen.FOOTER;
                out.addAll(MenuScreen.header(width, s, text.component(menu.title()), buttons, labels));
                tail.add(MenuScreen.footer(width, height, s, idle));
            }
        }

        Set<Integer> taken = new HashSet<>();
        for (MenuFile.Widget w : menu.widgets()) {
            if (w.whenPage() > 0 && w.whenPage() != page) continue;
            if (!w.whenPermission().isEmpty() && !view.allowed().test(w.whenPermission())) continue;
            String caption = text.plain(w.text()), description = text.plain(w.description());
            if (w.type() == MenuFile.Type.TILE && w.slot() >= 0 && grid != null) {
                if (w.slot() >= grid.slots()) continue;
                InventoryScreenLayout.Slot slot = new InventoryScreenLayout.Slot(w.slot(), view.icon().apply(w.icon()), Component.text(caption),
                        description.isBlank() ? List.of() : List.of(Component.text(description)), 1, false);
                List<ScreenWidget> parts = InventoryScreenLayout.tile(grid, w.id(), w.id() + ".icon", w.id() + ".amount", w.id() + ".name", slot);
                out.add(coloured(parts.get(0), w).withEnabled(w.enabled()));
                out.addAll(parts.subList(1, parts.size()));
                taken.add(w.slot());
                hints.put(w.id(), InventoryScreenLayout.hint(slot));
                continue;
            }
            double u = w.u() * s, v = w.v() * s, ww = w.width() * s, hh = w.height() * s;
            switch (w.type()) {
                case LABEL -> out.add(styled(ScreenWidget.label(w.id(), u, v, ww, hh, text.component(w.text())), w, s));
                case BUTTON -> out.add(styled(ScreenWidget.button(w.id(), u, v, ww, hh, text.component(w.text())), w, s));
                case FILL -> out.add(ScreenWidget.fill(w.id(), u, v, ww, hh, w.fill() == null ? FILL_DEFAULT : w.fill()));
                case ICON -> out.add(ScreenWidget.icon(w.id(), u, v, ww, hh, view.icon().apply(w.icon())));
                case TILE -> {
                    // as the main menu builds a tile: box with the short caption at its bottom edge, icon on top
                    double icon = Math.min(TILE_ICON * s, Math.min(ww, hh));
                    List<ScreenWidget> parts = MenuScreen.tile(w.id(), w.id() + ".icon", u, v, ww, hh, icon, v + TILE_ICON_DROP * s, caption,
                            view.icon().apply(w.icon()), (w.textHeight() > 0 ? w.textHeight() : TILE_LABEL) * s);
                    out.add(coloured(parts.get(0), w).withEnabled(w.enabled()).withAlign(w.align()));
                    out.add(parts.get(1));
                }
            }
            if (w.type().clickable() && !(caption.isEmpty() && description.isEmpty()))
                hints.put(w.id(), Component.text(caption + (description.isEmpty() ? "" : (caption.isEmpty() ? "" : " – ") + description)));
        }
        if (grid != null) for (int slot = 0; slot < grid.slots(); slot++)
            if (!taken.contains(slot)) out.add(InventoryScreenLayout.empty(grid, EMPTY_PREFIX + slot, slot));
        out.addAll(tail);

        ScreenLayout layout = new ScreenLayout(width, height, out);
        double k = shrinkFactor(width, height, profile);
        if (k < 1) layout = scaled(layout, k);
        return new Built(layout, Map.copyOf(hints), idle, footerId, k, page);
    }

    /** 1 when the panel with its border fits the display, else the factor that makes it fit. */
    static double shrinkFactor(double width, double height, ScreenProfile profile) {
        double roomW = profile.viewWidth() - ScreenRuntime.PANEL_BORDER, roomH = profile.viewHeight() - ScreenRuntime.PANEL_BORDER;
        if (width <= roomW + 1e-9 && height <= roomH + 1e-9) return 1;
        return Math.max(0.05, Math.min(roomW / width, roomH / height));
    }

    /** The same layout with every position, size and text height multiplied by {@code k}. */
    static ScreenLayout scaled(ScreenLayout layout, double k) {
        List<ScreenWidget> out = new ArrayList<>();
        for (ScreenWidget w : layout.widgets()) {
            boolean hasText = w.kind() == ScreenWidget.Kind.LABEL || w.kind() == ScreenWidget.Kind.BUTTON;
            // a text without its own height is as high as its box, but at most TEXT_MAX: that cap has to shrink too
            double textHeight = w.textHeight() > 0 ? w.textHeight() * k : hasText && w.height() > ScreenRuntime.TEXT_MAX ? ScreenRuntime.TEXT_MAX * k : 0;
            out.add(new ScreenWidget(w.id(), w.kind(), w.u() * k, w.v() * k, w.width() * k, w.height() * k, w.text(), w.enabled(), w.icon(),
                    textHeight, w.fill(), w.align(), w.selected(), w.highlight()));
        }
        return new ScreenLayout(layout.width() * k, layout.height() * k, out);
    }

    private static ScreenWidget styled(ScreenWidget widget, MenuFile.Widget w, double s) {
        ScreenWidget out = coloured(widget, w).withEnabled(w.enabled()).withAlign(w.align());
        return w.textHeight() > 0 ? out.withTextHeight(w.textHeight() * s) : out;
    }

    private static ScreenWidget coloured(ScreenWidget widget, MenuFile.Widget w) {
        ScreenWidget out = widget;
        if (w.fill() != null) out = out.withFill(w.fill());
        if (w.highlight() != null) out = out.withHighlight(w.highlight());
        return out;
    }
}
