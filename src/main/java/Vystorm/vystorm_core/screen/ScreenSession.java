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
     * {@code MapPalette}), row-major, {@code grid.pixelWidth() x grid.pixelHeight()}. Core keeps a reference to the
     * array to draw the map again after {@link #setLayout}. Ignored for unknown ids, other kinds and when the map is not shown (placeholder).
     * <p>Since 0.72.0 (capability {@code screen-map-partial}) Core compares the new pixels with what it
     * sent last and sends only the changed rectangle of each tile, within a per-viewer budget of
     * {@code Screens.MAP_BYTES_PER_TICK} per server tick (the rest follows in the next ticks, always the newest
     * pixels). A one-pixel change costs one small packet instead of every tile. Core builds its own copies for the
     * comparison, so changing one array in place and calling {@code setMap} again with it works too. A frame that
     * changes more than the budget is spread over several ticks (tiles of an 8x8 full-frame animation can show
     * different frames for a moment).
     */
    default void setMap(String widgetId, byte[] colours) { }
    /** True when the map widget is really drawn (PINNED with Core's shader and client-only frames), not its placeholder. */
    default boolean showsMap(String widgetId) { return false; }
    /**
     * Asks for {@link ScreenInput.Pointer} samples while the pointer is over this widget (since 0.72.0, capability
     * {@code screen-pointer}), at most one per server tick and only when the pointer moved; {@code false} stops them.
     * Meant for map canvases (brush outline, hover on swatches drawn into the map). Kept across {@link #setLayout}
     * for widgets of the same id. Screens that never call this pay nothing. True when this Core delivers samples.
     */
    default boolean trackPointer(String widgetId, boolean on) { return false; }
    /**
     * Asks for {@link ScreenInput.Press} (since 0.72.0, capability {@code screen-press}): right button down and up, for "hold to
     * paint". {@code false} stops them. True when this Core delivers them.
     */
    default boolean trackPress(boolean on) { return false; }
    boolean isOpen();
    void close();
}
