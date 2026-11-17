package Vystorm.vystorm_core.screen.internal;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Glyph building for the HUD corner window (since 0.59.0). The window is made of boss bar titles (one per row); the
 * Core shader moves text whose colour has red = 1 to a 176 px wide window at the top right: green = half the row width
 * (the client centres each title), blue = palette index (see {@code vystormHudColor} in {@code text.vsh}). Widths use the
 * advances of the default Minecraft font, so rows start exactly at the window's left edge. Pure, tested without a server.
 */
public final class HudGlyphs {
    public static final String FONT = "vystorm_screens:hud";
    public static final int WINDOW_WIDTH = 176, PADDING = 6, SEGMENT_WIDTH = 7, SEGMENTS = 12;
    public static final int PANEL2_HEIGHT = 33, PANEL3_HEIGHT = 52;
    static final char PANEL2 = '', PANEL3 = '', SEGMENT = '';
    /** Spaces: negative powers of two from U+E200, positive from U+E210. */
    static final int SPACE_NEG = 0xE200, SPACE_POS = 0xE210;
    /** Advance of an item sprite object component in text (8 px glyph + 1). */
    public static final int ICON_ADVANCE = 9;

    /** Palette indices of the shader. */
    public static final int TEXT = 0, MUTED = 1, GOLD = 2, OK = 3, BAD = 4, PANEL = 5, EMPTY = 6, PANEL_GLOW = 7, PURPLE = 8;

    private HudGlyphs() {}

    public static String fontJson() {
        StringBuilder spaces = new StringBuilder();
        for (int i = 0; i < 9; i++) spaces.append(i == 0 ? "" : ",").append("\"").append(unicode(SPACE_NEG + i)).append("\":").append(-(1 << i));
        for (int i = 0; i < 8; i++) spaces.append(",\"").append(unicode(SPACE_POS + i)).append("\":").append(1 << i);
        return "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"vystorm_screens:hud_panel2.png\",\"ascent\":10,\"height\":" + PANEL2_HEIGHT + ",\"chars\":[\"" + unicode(PANEL2) + "\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"vystorm_screens:hud_panel3.png\",\"ascent\":10,\"height\":" + PANEL3_HEIGHT + ",\"chars\":[\"" + unicode(PANEL3) + "\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"vystorm_screens:hud_bar.png\",\"ascent\":5,\"height\":5,\"chars\":[\"" + unicode(SEGMENT) + "\"]},"
                + "{\"type\":\"space\",\"advances\":{" + spaces + "}}]}";
    }

    private static String unicode(int c) { return String.format("\\u%04X", c); }

    /** Advance of a string in the default Minecraft font (glyph width + 1; space 4). */
    public static int width(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) w += advance(text.charAt(i));
        return w;
    }

    static int advance(char c) {
        if (c == ' ') return 4;
        if ("!',.:;i|·".indexOf(c) >= 0) return 2;
        if (c == 'l' || c == '`') return 3;
        if ("It[]".indexOf(c) >= 0) return 4;
        if ("\"()*<>fk{}".indexOf(c) >= 0) return 5;
        if (c == '@' || c == '~') return 7;
        if (c >= 0x2E80) return 9;   // CJK from the unifont: 8 px + 1
        return 6;
    }

    /** One coloured part of a row: text in the default font, or glyphs of the HUD font, or an icon. */
    public record Part(Object content, int palette, int advance) {
        static Part text(String text, int palette) { return new Part(text, palette, width(text)); }
        static Part hud(String glyphs, int palette, int advance) { return new Part(new HudRun(glyphs), palette, advance); }
        static Part icon(Component sprite) { return new Part(sprite, TEXT, ICON_ADVANCE); }
    }

    record HudRun(String glyphs) {}

    /** Glyphs of the HUD font that move the pen by {@code px} (negative = left). */
    static String space(int px) {
        StringBuilder out = new StringBuilder();
        int base = px < 0 ? SPACE_NEG : SPACE_POS, left = Math.abs(px);
        for (int bit = (px < 0 ? 8 : 7); bit >= 0; bit--) {
            while (left >= (1 << bit)) { out.append((char) (base + bit)); left -= 1 << bit; }
        }
        return out.toString();
    }

    /** Background panel for {@code rows} rows (2 or 3): slices of 8 px, net advance 0. */
    static Part panel(int rows, boolean glow) {
        char slice = rows >= 3 ? PANEL3 : PANEL2;
        StringBuilder glyphs = new StringBuilder();
        int slices = WINDOW_WIDTH / 8;
        for (int i = 0; i < slices; i++) glyphs.append(slice).append(space(-1));   // a bitmap glyph advances 9 for 8 px
        glyphs.append(space(-slices * 8));
        return Part.hud(glyphs.toString(), glow ? PANEL_GLOW : PANEL, 0);
    }

    /** XP bar of {@link #SEGMENTS} segments, {@code filled} share in 0..1. */
    static List<Part> bar(double filled, int colour) {
        int on = (int) Math.round(Math.max(0, Math.min(1, filled)) * SEGMENTS);
        List<Part> parts = new ArrayList<>();
        if (on > 0) parts.add(Part.hud(String.valueOf(SEGMENT).repeat(on), colour, on * (SEGMENT_WIDTH + 1)));
        if (on < SEGMENTS) parts.add(Part.hud(String.valueOf(SEGMENT).repeat(SEGMENTS - on), EMPTY, (SEGMENTS - on) * (SEGMENT_WIDTH + 1)));
        return parts;
    }

    /** Pushes the pen right by {@code px}. */
    static Part gap(int px) { return Part.hud(space(px), TEXT, px); }

    /** Total advance of a row. */
    static int width(List<Part> parts) {
        int w = 0;
        for (Part p : parts) w += p.advance();
        return w;
    }

    /** The boss bar title for a row: every part coloured (1, half width, palette). */
    static Component row(List<Part> parts) {
        int half = Math.max(0, Math.min(255, width(parts) / 2));
        Component out = Component.empty();
        for (Part p : parts) {
            TextColor colour = TextColor.color(1, half, p.palette());
            Component piece = switch (p.content()) {
                case HudRun run -> Component.text(run.glyphs()).font(Key.key(FONT)).color(colour);
                case String text -> Component.text(text).color(colour);
                case Component sprite -> sprite.color(colour);
                default -> Component.empty();
            };
            out = out.append(piece);
        }
        return out;
    }

    /**
     * The rows of a window: row 0 = panel, icon, title and right-aligned value; row 1 = bar and bar text (or the line
     * when there is no bar); row 2 = optional footer.
     */
    public static List<Component> window(Component icon, String title, String value, double progress, int barColour,
                                         String barText, String footer, boolean glow) {
        boolean hasBar = progress >= 0;
        int rows = footer == null || footer.isEmpty() ? 2 : 3;
        List<Component> out = new ArrayList<>();
        int titleColour = glow ? GOLD : TEXT;

        List<Part> r0 = new ArrayList<>();
        r0.add(panel(rows, glow));
        r0.add(gap(PADDING));
        if (icon != null) { r0.add(Part.icon(icon)); r0.add(gap(3)); }
        r0.add(Part.text(title, titleColour));
        if (value != null && !value.isEmpty()) {
            int used = width(r0), right = width(value);
            r0.add(gap(Math.max(4, WINDOW_WIDTH - PADDING - used - right)));
            r0.add(Part.text(value, glow ? GOLD : MUTED));
        }
        out.add(row(r0));

        List<Part> r1 = new ArrayList<>();
        r1.add(gap(PADDING));
        if (hasBar) {
            r1.addAll(bar(progress, barColour));
            if (barText != null && !barText.isEmpty()) { r1.add(gap(4)); r1.add(Part.text(barText, MUTED)); }
        } else if (barText != null) {
            r1.add(Part.text(barText, MUTED));
        }
        out.add(row(r1));

        if (rows == 3) {
            List<Part> r2 = new ArrayList<>();
            r2.add(gap(PADDING));
            r2.add(Part.text(footer, glow ? GOLD : OK));
            out.add(row(r2));
        }
        return out;
    }
}
