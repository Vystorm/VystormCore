package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class ScreenListLayoutTest {
    static final ScreenListLayout.Sizes Z = new ScreenListLayout.Sizes(1.0);
    static final double W = 1.8;

    static ScreenWidget widget(ScreenListLayout.Placed placed, String id) {
        return placed.widgets().stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no widget " + id));
    }

    static String text(ScreenWidget w) { return ScreenListLayout.plain((Component) w.text()); }

    @Test
    void givenEntry_whenPlaced_thenRowSpansTheWidthNameLeftValueRightDescriptionBelow() {
        ScreenListLayout.Row row = ScreenListLayout.Row.entry("r1", null, Component.text("Sound when mentioned"),
                Component.text("ON").color(TextColor.color(ScreenListLayout.OK)), Component.text("Plays a sound when someone writes your name"), true);
        ScreenListLayout.Placed placed = ScreenListLayout.place(row, 0.1, 0.2, W, Z);
        ScreenWidget box = widget(placed, "r1");
        assertEquals(ScreenWidget.Kind.BUTTON, box.kind(), "the whole row is the click target");
        assertEquals(0.1, box.u(), 1e-9);
        assertEquals(W, box.width(), 1e-9);
        ScreenWidget name = widget(placed, "r1.n0"), value = widget(placed, "r1.v"), desc = widget(placed, "r1.d0");
        assertEquals("Sound when mentioned", text(name));
        assertEquals(ScreenWidget.Align.START, name.align());
        assertEquals("ON", text(value));
        assertEquals(ScreenWidget.Align.END, value.align());
        assertEquals(0.1 + W - Z.inset(), value.u() + value.width(), 1e-9, "value column ends at the right edge of the row");
        assertTrue(name.u() + name.width() <= value.u() + 1e-9, "name and value columns do not overlap");
        assertEquals("Plays a sound when someone writes your name", text(desc));
        assertTrue(desc.v() >= name.v() + name.height() - 1e-9, "description under the name");
        assertEquals(ScreenListLayout.MUTED, ((Component) desc.text()).color().value(), "description is dimmer");
        for (ScreenWidget w : placed.widgets()) {
            assertTrue(w.v() >= 0.2 - 1e-9 && w.v() + w.height() <= 0.2 + placed.height() + 1e-9, "inside the row: " + w);
            assertFalse(w != box && w.clickable(), "only the row reacts to clicks: " + w);
        }
    }

    @Test
    void givenLongName_whenPlaced_thenItWrapsAndIsCutOnlyAtTheEndOfTheSecondLine() {
        String longName = "Show the coordinates of the death point in the chat for every player who died near you";
        ScreenListLayout.Row row = ScreenListLayout.Row.entry("r", null, Component.text(longName), Component.text("OFF"), null, true);
        double narrow = 0.9;
        ScreenListLayout.Placed placed = ScreenListLayout.place(row, 0, 0, narrow, Z);
        String first = text(widget(placed, "r.n0")), second = text(widget(placed, "r.n1"));
        assertTrue(first.length() > 15, "first line keeps whole words, not a few characters: " + first);
        assertTrue(longName.startsWith(first), first);
        assertTrue(second.endsWith("…"), "cut with an ellipsis at the end: " + second);
        assertTrue(placed.widgets().stream().noneMatch(w -> w.id().equals("r.n2")), "at most two name lines");
        for (String line : List.of(first, second)) {
            assertTrue(ScreenListLayout.textW(line, Z.text()) <= widget(placed, "r.n0").width() + 1e-9, "line fits without shrinking: " + line);
        }
        assertTrue(placed.height() > ScreenListLayout.height(ScreenListLayout.Row.entry("s", null, Component.text("Short"), Component.text("OFF"), null, true), narrow, Z),
                "two lines make the row taller");
    }

    @Test
    void givenWrap_whenWordsFit_thenNoEllipsisAndLongWordsAreCut() {
        assertEquals(List.of("alpha beta"), ScreenListLayout.wrap("alpha beta", 10, Z.text(), 2));
        List<String> cut = ScreenListLayout.wrap("x".repeat(200), 0.5, Z.text(), 2);
        assertEquals(2, cut.size());
        assertTrue(cut.get(1).endsWith("…"));
        assertTrue(ScreenListLayout.wrap("   ", 1, Z.text(), 2).isEmpty());
        assertEquals("abc", ScreenListLayout.cut("abc", 10, Z.text()));
        assertTrue(ScreenListLayout.cut("abcdefghijklmnopqrstuvwxyz", 0.2, Z.text()).endsWith("…"));
    }

    @Test
    void givenLongDescription_whenWrappedToTwoLines_thenNoWordIsCutInTheMiddle() {
        // 0.66.1: /vadmin features showed "… opens the main me…": the last line now ends after a whole word
        String text = "Sneak and left-click twice quickly into the air opens the main menu while you build, mine or fight on the server. ".repeat(3).strip();
        java.util.Set<String> words = new java.util.HashSet<>(List.of(text.split(" ")));
        for (double w : new double[] {0.4, 0.5, 0.6, 0.8}) {
            List<String> lines = ScreenListLayout.wrap(text, w, Z.small(), 2);
            assertEquals(2, lines.size(), "w=" + w);
            assertTrue(lines.get(1).endsWith("…"), lines.toString());
            for (String line : lines) {
                assertTrue(ScreenListLayout.textW(line, Z.small()) <= w + 1e-9, "fits: " + line);
                for (String word : line.replace("…", "").split(" ")) assertTrue(words.contains(word), "whole word '" + word + "' in " + lines);
            }
        }
        List<String> chinese = ScreenListLayout.wrap("潜行并空手右键其他玩家打开玩家菜单".repeat(6), 0.6, Z.small(), 2);
        assertEquals(2, chinese.size());
        assertTrue(chinese.get(1).endsWith("…") && ScreenListLayout.textW(chinese.get(1), Z.small()) <= 0.6 + 1e-9, "text without spaces is cut by characters");
    }

    @Test
    void givenLongValue_whenPlaced_thenValueColumnIsCappedAndCut() {
        ScreenListLayout.Row row = ScreenListLayout.Row.entry("r", null, Component.text("Join message"),
                Component.text("\"Welcome to our wonderful server, have fun and be nice to the others in the chat\""), null, true);
        ScreenListLayout.Placed placed = ScreenListLayout.place(row, 0, 0, W, Z);
        ScreenWidget value = widget(placed, "r.v");
        assertTrue(value.width() <= W * 0.42 + 1e-9, "the value takes at most 42 % of the row");
        assertTrue(text(value).endsWith("…"));
        assertEquals("Join message", text(widget(placed, "r.n0")), "the name keeps its room");
    }

    @Test
    void givenHeading_whenPlaced_thenFullWidthSeparatorWithGoldName() {
        ScreenListLayout.Placed placed = ScreenListLayout.place(ScreenListLayout.Row.heading("h", Component.text("Chat"), Component.text("15/15 on"), false), 0, 0, W, Z);
        ScreenWidget box = widget(placed, "h");
        assertEquals(ScreenWidget.Kind.FILL, box.kind(), "a heading without a target is no button");
        assertEquals(W, box.width(), 1e-9);
        assertEquals(ScreenListLayout.GOLD, ((Component) widget(placed, "h.n0").text()).color().value());
        assertEquals("15/15 on", text(widget(placed, "h.v")));
        assertEquals(W, widget(placed, "h.line").width(), 1e-9);
    }

    static List<ScreenListLayout.Row> rows(int headings, int perHeading) {
        List<ScreenListLayout.Row> rows = new ArrayList<>();
        for (int h = 0; h < headings; h++) {
            rows.add(ScreenListLayout.Row.heading("r" + rows.size(), Component.text("Plugin " + h), null, false));
            for (int i = 0; i < perHeading; i++)
                rows.add(ScreenListLayout.Row.entry("r" + rows.size(), null, Component.text("Feature " + i), Component.text("ON"), Component.text("Does something"), true));
        }
        return rows;
    }

    @Test
    void givenManyRows_whenWindowed_thenOnlyFittingRowsAndPositionCountsEntries() {
        List<ScreenListLayout.Row> rows = rows(3, 9);   // 3 headings + 27 entries
        double height = 0.8;
        ScreenListLayout.Window top = ScreenListLayout.window(rows, 0, W, height, Z);
        assertEquals(0, top.from());
        assertTrue(top.to() > 1 && top.to() < rows.size());
        double used = 0;
        for (int i = top.from(); i < top.to(); i++) used += ScreenListLayout.height(rows.get(i), W, Z);
        assertTrue(used - Z.gap() <= height + 1e-9, "the window fits");
        int[] pos = ScreenListLayout.position(rows, top);
        assertEquals(1, pos[0]);
        assertEquals(top.to() - 1, pos[1], "the heading is not counted");
        assertEquals(27, pos[2]);

        int max = ScreenListLayout.maxOffset(rows, W, height, Z);
        ScreenListLayout.Window end = ScreenListLayout.window(rows, max, W, height, Z);
        assertEquals(rows.size(), end.to(), "the last offset shows the last row");
        assertEquals(27, ScreenListLayout.position(rows, end)[1]);
        assertTrue(ScreenListLayout.window(rows, max - 1, W, height, Z).to() <= rows.size());
        assertEquals(0, ScreenListLayout.maxOffset(rows(1, 2), W, height, Z), "a short list does not scroll");
    }
}
