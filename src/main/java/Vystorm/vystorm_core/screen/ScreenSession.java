package Vystorm.vystorm_core.screen;

import org.bukkit.entity.Player;

/** An open screen of one player (since 0.57.0). All methods on the server thread. */
public interface ScreenSession {
    Player player();
    ScreenSpec spec();
    /** PINNED or FLOATING (never AUTO). */
    ScreenSpec.Mode mode();
    /** Replaces a widget's text (String or Adventure Component) and redraws it. Unknown ids are ignored. */
    void setText(String widgetId, Object text);
    /** Enables or disables a button or input and redraws it. */
    void setEnabled(String widgetId, boolean enabled);
    /** Replaces all widgets (for example after scrolling); the panel keeps its place, size and backdrop. */
    void setLayout(ScreenLayout layout);
    /**
     * Replaces the pixels of a {@link ScreenWidget.Kind#MAP} widget (since 0.59.0): map colour bytes (as
     * {@code MapPalette}), row-major, {@code grid.pixelWidth() x grid.pixelHeight()}. Core keeps the array, so pass a
     * fresh one per change. Ignored for unknown ids, other kinds and when the map is not shown (placeholder).
     */
    default void setMap(String widgetId, byte[] colours) { }
    /** True when the map widget is really drawn (PINNED with Core's shader and client-only frames), not its placeholder. */
    default boolean showsMap(String widgetId) { return false; }
    boolean isOpen();
    void close();
}
