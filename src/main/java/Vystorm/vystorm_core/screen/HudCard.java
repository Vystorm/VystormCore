package Vystorm.vystorm_core.screen;

/**
 * Content of the HUD corner window (since 0.59.0): a small panel at the top right of the screen, drawn in the GUI layer
 * (works with shader packs). {@code icon} is a Bukkit {@code Material} (item sprite) or null; {@code progress} 0..1 shows
 * a segmented bar, a negative value shows none; {@code footer} adds a third row; {@code highlight} makes the window
 * glow gold (level-ups, rewards). Texts are plain strings in the default font (formatting is not kept).
 */
public record HudCard(Object icon, String title, String value, double progress, Accent accent, String barText, String footer,
                      boolean highlight) {
    /** Colour of the bar. */
    public enum Accent { GOLD, GREEN, RED, PURPLE }

    public HudCard {
        title = title == null ? "" : title;
        accent = accent == null ? Accent.GOLD : accent;
    }

    /** Title and value with a bar. */
    public static HudCard of(Object icon, String title, String value, double progress, String barText) {
        return new HudCard(icon, title, value, progress, Accent.GOLD, barText, null, false);
    }

    public HudCard withFooter(String text) { return new HudCard(icon, title, value, progress, accent, barText, text, highlight); }
    public HudCard withAccent(Accent colour) { return new HudCard(icon, title, value, progress, colour, barText, footer, highlight); }
    public HudCard highlighted() { return new HudCard(icon, title, value, progress, accent, barText, footer, true); }
}
