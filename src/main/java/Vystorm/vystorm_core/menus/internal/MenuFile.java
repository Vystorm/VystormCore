package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.List;

/**
 * One menu file of the format {@code vystorm-menu} version 1 after validation: every value is in range, every list is
 * immutable. Lengths are panel units for UI size 1, as the file holds them. Texts are the MiniMessage sources;
 * placeholders are resolved per viewer when the menu is shown.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record MenuFile(String id, String title, String command, String permission, Frame frame, double width, double height,
                       int columns, int rows, List<String> header, String footer, ScreenSpec.Backdrop backdrop, ScreenSpec.Mode mode,
                       int pages, boolean mainMenu, String icon, String group, List<Widget> widgets, boolean placeholders) {
    /** What Core draws around the widgets. */
    public enum Frame { MENU, CHEST, NONE }

    public enum Type { LABEL, BUTTON, TILE, ICON, FILL;
        public boolean clickable() { return this == BUTTON || this == TILE; }
    }

    public enum ActionKind { PLAYER, CONSOLE, MESSAGE, OPEN, BACK, CLOSE }

    /** One step of a click; {@code text} is the command, message or target menu id (empty for back and close). */
    public record Action(ActionKind kind, String text) {}

    /** What a left or a right click does; {@code permission} is empty or {@code node} / {@code !node}. */
    public record Click(List<Action> actions, int cooldown, String permission) {
        public static final Click NONE = new Click(List.of(), 0, "");
        public Click { actions = List.copyOf(actions); }
        public boolean empty() { return actions.isEmpty(); }
    }

    /**
     * A widget as the file has it. {@code slot} is -1 unless the tile sits in a cell of a chest frame. {@code fill}
     * and {@code highlight} are ARGB colours or null for Core's own. {@code whenPage} 0 = on every page.
     */
    public record Widget(String id, Type type, int slot, double u, double v, double width, double height, String text, String icon,
                         String description, ScreenWidget.Align align, double textHeight, Integer fill, Integer highlight, boolean enabled,
                         String whenPermission, int whenPage, Click click, Click rightClick) {}

    public MenuFile {
        header = List.copyOf(header);
        widgets = List.copyOf(widgets);
    }

    /** Key of the screen and of its place in the Back history: {@code menus:<id>}. */
    public String key() { return KEY_PREFIX + id; }

    public static final String KEY_PREFIX = "menus:";

    /** The widget with this id, or null. */
    public Widget widget(String widgetId) {
        for (Widget widget : widgets) if (widget.id().equals(widgetId)) return widget;
        return null;
    }
}
