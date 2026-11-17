package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class HudGlyphsTest {
    /** Pen movement of HUD-font glyphs as the font defines them. */
    static int advance(String glyphs) {
        int px = 0;
        for (char c : glyphs.toCharArray()) {
            if (c >= HudGlyphs.SPACE_NEG && c < HudGlyphs.SPACE_NEG + 9) px -= 1 << (c - HudGlyphs.SPACE_NEG);
            else if (c >= HudGlyphs.SPACE_POS && c < HudGlyphs.SPACE_POS + 8) px += 1 << (c - HudGlyphs.SPACE_POS);
            else if (c == HudGlyphs.SEGMENT) px += HudGlyphs.SEGMENT_WIDTH + 1;
            else px += 9;   // panel slice: 8 px + 1
        }
        return px;
    }

    @Test
    void givenSpaces_whenComposed_thenTheyMoveExactlyThatFar() {
        for (int px : new int[] { -1, -7, -176, -300, 1, 6, 125, 200 }) assertEquals(px, advance(HudGlyphs.space(px)), "space " + px);
    }

    @Test
    void givenPanel_whenBuilt_thenItCoversTheWindowAndMovesThePenBack() {
        HudGlyphs.Part panel = HudGlyphs.panel(3, false);
        assertEquals(0, advance(((HudGlyphs.HudRun) panel.content()).glyphs()));
        assertEquals(0, panel.advance());
    }

    @Test
    void givenDefaultFont_whenMeasured_thenUsesMinecraftAdvances() {
        assertEquals(6 + 4 + 2, HudGlyphs.width("a i"));
        assertEquals(6 * 2 + 3, HudGlyphs.width("Lvl"));
    }

    @Test
    void givenWindow_whenBuilt_thenEveryRowCarriesTheMarkerAndItsHalfWidth() {
        List<Component> rows = HudGlyphs.window(null, "Mining", "Lv 42", 0.5, HudGlyphs.GOLD, "340 / 540 XP", "+12 XP", false);
        assertEquals(3, rows.size());
        for (Component row : rows) {
            for (Component part : row.children()) {
                TextColor c = part.color();
                assertNotNull(c);
                assertEquals(1, c.red(), "marker");
            }
        }
        // row 1: padding + 16 segments + gap + text; green = half of it
        int expected = (HudGlyphs.PADDING + HudGlyphs.SEGMENTS * (HudGlyphs.SEGMENT_WIDTH + 1) + 4 + HudGlyphs.width("340 / 540 XP")) / 2;
        assertEquals(expected, rows.get(1).children().getFirst().color().green());
    }

    @Test
    void givenNoFooter_whenBuilt_thenTwoRowsAndTheValueSitsAtTheRightEdge() {
        List<Component> rows = HudGlyphs.window(null, "Mining", "Lv 42", -1, HudGlyphs.GOLD, "only text", null, true);
        assertEquals(2, rows.size());
        int total = 0;
        for (Component part : rows.getFirst().children()) {
            String text = ((TextComponent) part).content();
            total += part.font() != null ? advance(text) : HudGlyphs.width(text);
        }
        assertEquals(HudGlyphs.WINDOW_WIDTH - HudGlyphs.PADDING, total, "value ends PADDING px before the right edge");
    }

    @Test
    void givenFontJson_whenParsed_thenEveryBitmapAscentFitsItsHeight() {
        var root = com.google.gson.JsonParser.parseString(HudGlyphs.fontJson()).getAsJsonObject();
        for (var provider : root.getAsJsonArray("providers")) {
            var p = provider.getAsJsonObject();
            if (!"bitmap".equals(p.get("type").getAsString())) continue;
            assertTrue(p.get("ascent").getAsInt() <= p.get("height").getAsInt(), "Minecraft rejects the whole font otherwise: " + p);
        }
    }
}
