package Vystorm.vystorm_core.menus.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.internal.InventoryScreenLayout;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The loader and the editor must put every widget at the same place. The two demo menus in
 * {@code src/test/resources/menus} are the editor's own ({@code Format.demoMenu()}, {@code Format.demoChest()}), and
 * the expected numbers below were computed by the editor's layout module.
 */
class FileMenuLayoutTest {
    static final ScreenProfile HD = new ScreenProfile(1280.0 / 720.0, 70, 1.0);
    private final Map<String, FileMenuLayout.Built> built = new HashMap<>();

    static String resource(String name) throws Exception {
        try (InputStream in = FileMenuLayoutTest.class.getClassLoader().getResourceAsStream("menus/" + name)) {
            assertNotNull(in, "missing test resource menus/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static MenuFile demo(String id) throws Exception {
        MenuFileParser.Parsed parsed = MenuFileParser.parse(id + ".json", resource(id + ".json"), name -> true);
        assertTrue(parsed.ok(), id + ": " + parsed.errors());
        return parsed.menu();
    }

    /** A viewer called Player without any permission (so widgets for {@code !node} are shown). */
    static FileMenuLayout.View view(ScreenProfile profile, int page, Predicate<String> allowed, int pages) {
        return new FileMenuLayout.View(profile, page, allowed, MenuText.of(null, "Player", page, pages, null), name -> Material.PAPER,
                Map.of("display", Component.text("Display"), "search", Component.text("Search"), "close", Component.text("Close")));
    }

    static FileMenuLayout.View view(ScreenProfile profile) { return view(profile, 1, permission -> permission.startsWith("!"), 1); }

    static ScreenWidget widget(ScreenLayout layout, String id) {
        return layout.widgets().stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no widget " + id));
    }

    static String plain(Object text) { return text instanceof Component c ? PlainTextComponentSerializer.plainText().serialize(c) : String.valueOf(text); }

    private FileMenuLayout.Built of(String menu) {
        return built.computeIfAbsent(menu, id -> {
            try { return FileMenuLayout.build(demo(id), view(HD)); } catch (Exception failed) { throw new AssertionError(failed); }
        });
    }

    private void panel(String menu, double width, double height, int widgets) {
        ScreenLayout layout = of(menu).layout();
        assertEquals(width, layout.width(), 1e-6, menu + " width");
        assertEquals(height, layout.height(), 1e-6, menu + " height");
        assertEquals(widgets, layout.widgets().size(), menu + " widget count");
    }

    private void box(String menu, String id, double u, double v, double width, double height) {
        ScreenWidget w = widget(of(menu).layout(), id);
        assertEquals(u, w.u(), 1e-6, menu + " " + id + " u");
        assertEquals(v, w.v(), 1e-6, menu + " " + id + " v");
        assertEquals(width, w.width(), 1e-6, menu + " " + id + " width");
        assertEquals(height, w.height(), 1e-6, menu + " " + id + " height");
    }

    private void fits(String menu, boolean fits) { assertEquals(fits, of(menu).shrink() == 1.0, menu + " fits the display"); }

    @Test
    void givenTheEditorsDemoMenus_whenBuiltAt1280x720_thenWidgetsSitWhereTheEditorPutsThem() {
        // Printed by: node scripts/menu-format-expected.cjs <folder of the screens editor>
        // (lib/screen-layout.js and lib/menu-format.js of the editor, format vystorm-menu version 1, 2026-11-18)
        // main: panel and widgets at 1280x720
        panel("main", 2.04, 1.12, 57);
        box("main", "title", 0.05, 0.03, 1.22, 0.08);
        box("main", "display", 1.29, 0.03, 0.22, 0.07);
        box("main", "search", 1.53, 0.03, 0.22, 0.07);
        box("main", "close", 1.77, 0.03, 0.22, 0.07);
        box("main", "section-character", 0.05, 0.16, 1.94, 0.05);
        box("main", "herbarium", 0.325, 0.225, 0.19, 0.16);
        box("main", "herbarium.icon", 0.3775, 0.23, 0.085, 0.085);
        box("main", "market", 0.325, 0.465, 0.19, 0.16);
        box("main", "market.icon", 0.3775, 0.47, 0.085, 0.085);
        box("main", "rent-buy-plots", 1.725, 0.465, 0.19, 0.16);
        box("main", "party", 1.625, 0.705, 0.19, 0.16);
        box("main", "section-world", 0.05, 0.88, 1.94, 0.05);
        box("main", "footer", 0.05, 1.05, 1.94, 0.045);
        fits("main", true);
        // locks-general: panel and widgets at 1280x720
        panel("locks-general", 1.615035, 1.204357, 75);
        box("locks-general", "title", 0.04, 0.025, 1.327035, 0.06);
        box("locks-general", "close", 1.375035, 0.025, 0.2, 0.06);
        box("locks-general", "head-line", 0, 0.1, 1.615035, 0.004);
        box("locks-general", "auto-protect", 0.044, 0.112, 0.162559, 0.162559);
        box("locks-general", "auto-protect.icon", 0.080934, 0.125056, 0.088691, 0.088691);
        box("locks-general", "auto-protect.name", 0.048, 0.220569, 0.154559, 0.02729);
        box("locks-general", "max-buddies", 1.067357, 0.112, 0.162559, 0.162559);
        box("locks-general", "back", 0.726238, 0.964797, 0.162559, 0.162559);
        box("locks-general", "back.icon", 0.763172, 0.977853, 0.088691, 0.088691);
        box("locks-general", "back.name", 0.730238, 1.073367, 0.154559, 0.02729);
        box("locks-general", "foot-line", 0, 1.139357, 1.615035, 0.004);
        box("locks-general", "hint", 0.04, 1.155357, 1.535035, 0.034);
        fits("locks-general", true);
    }

    @Test
    void givenTheDemoMenu_whenBuilt_thenTextsFollowTheFormat() {
        ScreenLayout layout = of("main").layout();
        assertEquals("Vystorm · Main menu", plain(widget(layout, "title").text()));
        assertEquals("Hello Player · point at a tile to read what it does", plain(widget(layout, "footer").text()));
        assertEquals("Painting exhibiti…", plain(widget(layout, "painting-exhibition").text()), "tile captions are cut to 18 characters");
        assertEquals(0.04, widget(layout, "market").textHeight(), 1e-9);
        assertEquals(0x50252A33, widget(layout, "market").fill());
        assertTrue(widget(layout, "market").clickable());
        assertFalse(widget(layout, "market.icon").clickable());
        assertEquals("Market – Market, own offers, list an item and chest shops", plain(of("main").hints().get("market")));
        assertEquals("footer", of("main").footerId());
        assertNull(of("main").hints().get("close"), "header buttons leave the footer as it is");
    }

    @Test
    void givenTheChestDemo_whenBuilt_thenTilesLookLikeCoresChestScreens() {
        FileMenuLayout.Built chest = of("locks-general");
        ScreenWidget tile = widget(chest.layout(), "auto-protect");
        assertEquals(InventoryScreenLayout.TILE, tile.fill());
        assertEquals(InventoryScreenLayout.TILE_LIT, tile.highlight());
        assertEquals("Auto protect", plain(widget(chest.layout(), "auto-protect.name").text()));
        assertEquals("Hopper protec…", plain(widget(chest.layout(), "hopper-protection.name").text()), "names are cut to 14 characters");
        assertEquals("Auto protect  ·  Protect containers automatically when they are placed.", plain(chest.hints().get("auto-protect")));
        assertEquals("hint", chest.footerId());
        assertEquals(54 - 8, chest.layout().widgets().stream().filter(w -> w.id().startsWith(FileMenuLayout.EMPTY_PREFIX)).count(), "free cells are drawn empty");
        assertEquals(chest.layout().widgets().size(), chest.layout().widgets().stream().map(ScreenWidget::id).distinct().count(), "ids are unique");
    }

    @Test
    void givenAPermissionCondition_whenTheViewerDoesNotMatch_thenTheWidgetIsLeftOut() throws Exception {
        MenuFile menu = demo("main");
        Set<String> guest = Set.of("vystorm.guest");
        FileMenuLayout.Built forGuest = FileMenuLayout.build(menu, view(HD, 1, p -> p.startsWith("!") != guest.contains(p.replace("!", "")), 1));
        assertTrue(forGuest.layout().widgets().stream().noneMatch(w -> w.id().equals("warehouse")), "!vystorm.guest hides the tile from a guest");
        assertTrue(forGuest.layout().widgets().stream().noneMatch(w -> w.id().equals("warehouse.icon")));
        assertNotNull(widget(of("main").layout(), "warehouse"));
        assertEquals(55, forGuest.layout().widgets().size());
    }

    @Test
    void givenAnotherUiSize_whenBuilt_thenEveryLengthOfTheFileIsMultiplied() throws Exception {
        MenuFile menu = demo("main");
        FileMenuLayout.Built small = FileMenuLayout.build(menu, view(new ScreenProfile(16.0 / 9.0, 70, 0.8)));
        assertEquals(2.04 * 0.8, small.layout().width(), 1e-9);
        ScreenWidget market = widget(small.layout(), "market");
        assertEquals(0.325 * 0.8, market.u(), 1e-9);
        assertEquals(0.19 * 0.8, market.width(), 1e-9);
        assertEquals(0.04 * 0.8, market.textHeight(), 1e-9);
        assertEquals(1.0, small.shrink());
    }

    @Test
    void givenAPanelLargerThanTheDisplay_whenBuilt_thenEverythingShrinksByOneFactor() throws Exception {
        MenuFile menu = demo("main");
        ScreenProfile narrow = new ScreenProfile(4.0 / 3.0, 70, 1.0);   // 1.867 wide: the 2.04 panel does not fit
        FileMenuLayout.Built shrunk = FileMenuLayout.build(menu, view(narrow));
        double k = shrunk.shrink();
        assertEquals((narrow.viewWidth() - ScreenRuntime.PANEL_BORDER) / 2.04, k, 1e-9);
        assertTrue(k < 1);
        assertEquals(narrow.viewWidth(), shrunk.layout().width() + ScreenRuntime.PANEL_BORDER, 1e-9, "panel and border fill the width exactly");
        FileMenuLayout.Built full = of("main");
        assertEquals(full.layout().widgets().size(), shrunk.layout().widgets().size(), "shrunk, not laid out anew");
        for (int i = 0; i < full.layout().widgets().size(); i++) {
            ScreenWidget a = full.layout().widgets().get(i), b = shrunk.layout().widgets().get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.u() * k, b.u(), 1e-9, a.id());
            assertEquals(a.v() * k, b.v(), 1e-9, a.id());
            assertEquals(a.width() * k, b.width(), 1e-9, a.id());
            assertEquals(a.height() * k, b.height(), 1e-9, a.id());
            if (a.textHeight() > 0) assertEquals(a.textHeight() * k, b.textHeight(), 1e-9, a.id());
        }
        assertEquals(0.04 * k, widget(shrunk.layout(), "market").textHeight(), 1e-9);
    }

    @Test
    void givenATallLabelWithoutTextHeight_whenShrunk_thenTheTextCapShrinksToo() {
        ScreenLayout layout = new ScreenLayout(1, 1, List.of(ScreenWidget.label("big", 0, 0, 1, 0.2, "Big"), ScreenWidget.label("small", 0, 0.3, 1, 0.05, "Small"),
                ScreenWidget.fill("line", 0, 0.5, 1, 0.2, 0xFF000000)));
        ScreenLayout half = FileMenuLayout.scaled(layout, 0.5);
        assertEquals(ScreenRuntime.TEXT_MAX * 0.5, widget(half, "big").textHeight(), 1e-9);
        assertEquals(0, widget(half, "small").textHeight(), 1e-9, "its text is as high as its box and shrinks with it");
        assertEquals(0, widget(half, "line").textHeight(), 1e-9);
        assertEquals(0.5, half.width(), 1e-9);
    }

    @Test
    void givenPagesAndAFrameWithoutHeader_whenBuilt_thenOnlyThePageIsShown() {
        String json = """
                { "format": "vystorm-menu", "version": 1, "id": "pages", "frame": "none", "size": { "width": 1.0, "height": 0.6 }, "pages": 2,
                  "widgets": [
                    { "id": "always", "type": "label", "u": 0.1, "v": 0.1, "width": 0.5, "height": 0.05, "text": "Page {page}/{pages}" },
                    { "id": "one", "type": "button", "u": 0.1, "v": 0.2, "width": 0.2, "height": 0.07, "text": "<gold>One", "when": { "page": 1 } },
                    { "id": "two", "type": "button", "u": 0.1, "v": 0.2, "width": 0.2, "height": 0.07, "text": "Two", "when": { "page": 2 }, "enabled": false },
                    { "id": "bar", "type": "fill", "u": 0.1, "v": 0.4, "width": 0.4, "height": 0.004 },
                    { "id": "gem", "type": "icon", "u": 0.6, "v": 0.1, "width": 0.1, "height": 0.1, "icon": "EMERALD" } ] }""";
        MenuFileParser.Parsed parsed = MenuFileParser.parse("pages.json", json, name -> true);
        assertTrue(parsed.ok(), parsed.errors().toString());
        FileMenuLayout.Built one = FileMenuLayout.build(parsed.menu(), view(HD, 1, p -> true, 2));
        FileMenuLayout.Built two = FileMenuLayout.build(parsed.menu(), view(HD, 2, p -> true, 2));
        assertEquals(List.of("always", "one", "bar", "gem"), one.layout().widgets().stream().map(ScreenWidget::id).toList());
        assertEquals(List.of("always", "two", "bar", "gem"), two.layout().widgets().stream().map(ScreenWidget::id).toList());
        assertEquals("Page 1/2", plain(widget(one.layout(), "always").text()));
        assertEquals("Page 2/2", plain(widget(two.layout(), "always").text()));
        assertNull(one.footerId(), "frame none has no footer");
        assertFalse(widget(two.layout(), "two").clickable());
        assertEquals(FileMenuLayout.FILL_DEFAULT, widget(one.layout(), "bar").fill());
        assertEquals(ScreenWidget.Kind.ICON, widget(one.layout(), "gem").kind());
        assertEquals(2, FileMenuLayout.build(parsed.menu(), view(HD, 9, p -> true, 2)).page(), "a page above the last is the last");
    }
}
