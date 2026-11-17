package Vystorm.vystorm_core.screen;

import java.util.Objects;

/**
 * One element of a {@link ScreenLayout} (since 0.57.0). Position {@code (u, v)} is the top-left corner in panel units
 * (one unit = one block at distance 1), {@code v} grows downwards from the top edge. {@code text} is a String or an
 * Adventure Component. {@code icon} is a Bukkit {@code Material} or {@code ItemStack} for {@link Kind#ICON} widgets
 * (drawn like an inventory icon, centred in the widget's box) and null otherwise. {@code textHeight} fixes the line
 * height of the text in panel units (0 = from the widget height); text that is too wide always shrinks to fit.
 *
 * <p>Since 0.58.0 every widget can have a box: {@code fill} is an ARGB colour drawn behind the whole widget box
 * ({@link #FILL_AUTO} = the kind's own colour: buttons and inputs slate, labels none; {@link #FILL_NONE} = no box).
 * {@link Kind#FILL} is a plain coloured rectangle. {@code align} places the text in its box, and {@code selected}
 * draws a button like the hovered one (gold), for chosen tabs and list rows.
 */
public record ScreenWidget(String id, Kind kind, double u, double v, double width, double height, Object text, boolean enabled,
                           Object icon, double textHeight, int fill, Align align, boolean selected, int highlight) {
    public enum Kind { LABEL, BUTTON, INPUT, ICON, FILL, MAP }
    public enum Align { START, CENTER, END }

    /**
     * Size of a {@link Kind#MAP} canvas in map tiles (since 0.59.0). Each tile shows {@link #TILE_WIDTH} x
     * {@link #TILE_HEIGHT} pixels (one row of each 128 px client map carries the shader header).
     */
    public record MapGrid(int columns, int rows) {
        public static final int TILE_WIDTH = 128, TILE_HEIGHT = 127;
        public MapGrid {
            if (columns < 1 || rows < 1 || columns > 8 || rows > 8) throw new IllegalArgumentException("Map grids are 1x1 to 8x8 tiles");
        }
        public int pixelWidth() { return columns * TILE_WIDTH; }
        public int pixelHeight() { return rows * TILE_HEIGHT; }
    }

    /** The kind's own box colour. */
    public static final int FILL_AUTO = 1;
    /** No box. */
    public static final int FILL_NONE = 0;

    public ScreenWidget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (!(width > 0) || !(height > 0)) throw new IllegalArgumentException("Widget " + id + " needs a positive size");
        text = text == null ? "" : text;
        if (kind == Kind.ICON && icon == null) throw new IllegalArgumentException("Icon widget " + id + " needs an icon");
        if (kind == Kind.MAP && !(icon instanceof MapGrid)) throw new IllegalArgumentException("Map widget " + id + " needs a MapGrid");
        textHeight = Double.isFinite(textHeight) && textHeight > 0 ? textHeight : 0;
        align = align == null ? Align.CENTER : align;
    }

    public ScreenWidget(String id, Kind kind, double u, double v, double width, double height, Object text, boolean enabled,
                        Object icon, double textHeight) {
        this(id, kind, u, v, width, height, text, enabled, icon, textHeight, FILL_AUTO, Align.CENTER, false, FILL_AUTO);
    }

    public ScreenWidget(String id, Kind kind, double u, double v, double width, double height, Object text, boolean enabled) {
        this(id, kind, u, v, width, height, text, enabled, null, 0);
    }

    public static ScreenWidget label(String id, double u, double v, double width, double height, Object text) { return new ScreenWidget(id, Kind.LABEL, u, v, width, height, text, true); }
    public static ScreenWidget button(String id, double u, double v, double width, double height, Object text) { return new ScreenWidget(id, Kind.BUTTON, u, v, width, height, text, true); }
    public static ScreenWidget input(String id, double u, double v, double width, double height, Object text) { return new ScreenWidget(id, Kind.INPUT, u, v, width, height, text, true); }
    /** An item icon ({@code Material} or {@code ItemStack}); never clickable on its own, put a button over it for that. */
    public static ScreenWidget icon(String id, double u, double v, double width, double height, Object icon) { return new ScreenWidget(id, Kind.ICON, u, v, width, height, "", true, icon, 0); }
    /** A plain rectangle in {@code argb} (bars, separators, colour swatches). @since 0.58.0 */
    public static ScreenWidget fill(String id, double u, double v, double width, double height, int argb) {
        return new ScreenWidget(id, Kind.FILL, u, v, width, height, "", true, null, 0, argb, Align.CENTER, false, FILL_AUTO);
    }

    public ScreenWidget withText(Object newText) { return new ScreenWidget(id, kind, u, v, width, height, newText, enabled, icon, textHeight, fill, align, selected, highlight); }
    public ScreenWidget withEnabled(boolean on) { return new ScreenWidget(id, kind, u, v, width, height, text, on, icon, textHeight, fill, align, selected, highlight); }
    /** Same text size for many widgets (for example all tile names), independent of each widget's height. */
    public ScreenWidget withTextHeight(double lineHeight) { return new ScreenWidget(id, kind, u, v, width, height, text, enabled, icon, lineHeight, fill, align, selected, highlight); }
    /** @since 0.58.0 */
    public ScreenWidget withFill(int argb) { return new ScreenWidget(id, kind, u, v, width, height, text, enabled, icon, textHeight, argb, align, selected, highlight); }
    /** @since 0.58.0 */
    public ScreenWidget withAlign(Align where) { return new ScreenWidget(id, kind, u, v, width, height, text, enabled, icon, textHeight, fill, where, selected, highlight); }
    /** @since 0.58.0 */
    public ScreenWidget withSelected(boolean on) { return new ScreenWidget(id, kind, u, v, width, height, text, enabled, icon, textHeight, fill, align, on, highlight); }

    /** Box colour while hovered or selected ({@link #FILL_AUTO} = Vystorm gold). Text turns dark on bright colours. @since 0.58.0 */
    public ScreenWidget withHighlight(int argb) { return new ScreenWidget(id, kind, u, v, width, height, text, enabled, icon, textHeight, fill, align, selected, argb); }

    /** Colour of the box while hovered or selected. */
    public int litFill() { return highlight == FILL_AUTO ? 0xF0E3B04B : highlight; }

    /** True when text on {@link #litFill()} should be dark. */
    public boolean litIsBright() {
        int c = litFill();
        double lum = 0.2126 * (c >> 16 & 0xFF) + 0.7152 * (c >> 8 & 0xFF) + 0.0722 * (c & 0xFF);
        return lum > 140;
    }

    /** Buttons, inputs and maps that are enabled react to clicks; labels and icons never do. */
    public boolean clickable() { return enabled && (kind == Kind.BUTTON || kind == Kind.INPUT || kind == Kind.MAP); }

    /**
     * A map canvas (since 0.59.0): {@code grid} tiles of 128 x 127 pixels, filled with
     * {@link ScreenSession#setMap}. Shown in PINNED screens only (it needs Core's pack shader); FLOATING screens and
     * servers without client-only frames show {@code text} in its place. Clicks on it carry the widget id; turn the
     * click position into a canvas pixel with {@link #mapPixel}. Keep other widgets off the map: the map is drawn above
     * them (only the pointer stays on top). For square pixels use {@code height = width * grid.pixelHeight() / grid.pixelWidth()}.
     */
    public static ScreenWidget map(String id, double u, double v, double width, double height, MapGrid grid, Object text) {
        return new ScreenWidget(id, Kind.MAP, u, v, width, height, text, true, grid, 0);
    }

    /** The canvas grid of a {@link Kind#MAP} widget, else null. */
    public MapGrid mapGrid() { return icon instanceof MapGrid grid ? grid : null; }

    /**
     * Canvas pixel {x, y} (y down) under the centre-origin panel point {@code (pu, pv)} from a {@link ScreenInput.Click},
     * clamped to the canvas; null for widgets that are not maps.
     */
    public int[] mapPixel(double pu, double pv, double panelWidth, double panelHeight) {
        MapGrid grid = mapGrid();
        if (grid == null) return null;
        double x = (pu + panelWidth / 2 - u) / width, y = (panelHeight / 2 - pv - v) / height;
        int px = (int) Math.floor(x * grid.pixelWidth()), py = (int) Math.floor(y * grid.pixelHeight());
        return new int[] { Math.max(0, Math.min(grid.pixelWidth() - 1, px)), Math.max(0, Math.min(grid.pixelHeight() - 1, py)) };
    }

    /** Colour of the box behind this widget in its resting state (alpha 0 = none). */
    public int restingFill() {
        if (fill != FILL_AUTO) return fill;
        return switch (kind) {
            case BUTTON -> enabled ? 0xD0303A48 : 0x80404040;
            case INPUT -> 0xD0252A33;
            case MAP -> 0xF0141820; // behind the tiles while they load, and behind the placeholder text
            case LABEL, ICON, FILL -> FILL_NONE;
        };
    }

    boolean contains(double x, double y) { return x >= u && x <= u + width && y >= v && y <= v + height; }
}
