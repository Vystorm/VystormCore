package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._uiMethods.autoGui.MenuEntryView;
import Vystorm.vystorm_core.screen.ScreenProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class MenuScreenTest {
    private static final MenuScreen.Metrics DEFAULT = MenuScreen.Metrics.of(ScreenProfile.DEFAULT);

    private static List<MenuEntryView> entries(int n) {
        return IntStream.range(0, n).mapToObj(i -> new MenuEntryView("p:m" + i, "Menu " + i, Material.STONE, "", "Desc " + i)).toList();
    }

    @Test void givenThirteenEntriesAndSixColumns_whenSection_thenHeaderAndThreeTileRows() {
        List<MenuScreen.Row> rows = new ArrayList<>();
        MenuScreen.addSection(rows, Component.text("General"), entries(13), 6);
        assertEquals(4, rows.size());
        assertNotNull(rows.get(0).title());
        assertEquals(6, rows.get(1).entries().size());
        assertEquals(1, rows.get(3).entries().size());
    }

    @Test void givenWiderScreen_thenMoreColumns() {
        MenuScreen.Metrics normal = MenuScreen.Metrics.of(new ScreenProfile(16.0 / 9, 70, 1.0));
        MenuScreen.Metrics ultrawide = MenuScreen.Metrics.of(new ScreenProfile(3840.0 / 1009, 70, 1.0));
        assertTrue(ultrawide.columns() > normal.columns());
        MenuScreen.Metrics big = MenuScreen.Metrics.of(new ScreenProfile(16.0 / 9, 70, 1.5));
        assertTrue(big.columns() < normal.columns(), "larger UI size, fewer columns");
        assertTrue(normal.width() <= ScreenProfile.DEFAULT.viewWidth(), "panel fits the view");
        assertTrue(normal.height() <= ScreenProfile.DEFAULT.viewHeight());
    }

    @Test void givenManySections_whenScrolling_thenEveryRowReachable() {
        List<MenuScreen.Row> rows = new ArrayList<>();
        for (int s = 0; s < 8; s++) MenuScreen.addSection(rows, Component.text("S" + s), entries(20), DEFAULT.columns());
        int max = MenuScreen.maxOffset(rows, DEFAULT);
        assertTrue(max > 0, "more rows than fit");
        assertEquals(rows.size(), max + MenuScreen.visibleRows(rows, max, DEFAULT), "last page shows the last row");
    }

    @Test void givenFewEntries_thenNoScrolling() {
        List<MenuScreen.Row> rows = new ArrayList<>();
        MenuScreen.addSection(rows, Component.text("General"), entries(5), DEFAULT.columns());
        assertEquals(0, MenuScreen.maxOffset(rows, DEFAULT));
    }

    @Test void givenTileIds_thenMappedToEntries() {
        List<MenuEntryView> list = entries(3);
        assertEquals("p:m2", MenuScreen.entry("t2", list).key());
        assertNull(MenuScreen.entry("t3", list));
        assertNull(MenuScreen.entry("search", list));
        assertNull(MenuScreen.entry("tx", list));
        assertNull(MenuScreen.entry(null, list));
    }

    @Test void givenLongOrColouredTitle_thenShortenedPlain() {
        assertEquals("Shops", MenuScreen.shorten("§6Shops"));
        String shortened = MenuScreen.shorten("A very long menu title for tiles");
        assertEquals(MenuScreen.LABEL_CHARS, shortened.length());
        assertTrue(shortened.endsWith("…"));
    }

    @Test void givenCategoryKeys_thenRecognised() {
        assertTrue(MenuScreen.isCategory("vystorm_core:category-admin"));
        assertTrue(MenuScreen.isCategory("vystorm_core:tutorials"));
        assertFalse(MenuScreen.isCategory("rails:network"));
    }

    @Test void givenUltrawide_thenColumnsCappedAndTilesKeepTheirSize() {
        MenuScreen.Metrics ultrawide = MenuScreen.Metrics.of(new ScreenProfile(3840.0 / 1009, 70, 1.0));
        assertTrue(ultrawide.columns() <= 12);
        assertEquals(0.2, ultrawide.tileWidth(), 1e-9);
        assertTrue(ultrawide.columns() * ultrawide.tileWidth() <= ultrawide.width(), "a full row fits the panel");
    }
}
