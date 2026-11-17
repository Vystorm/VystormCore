package Vystorm.vystorm_core.panel;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.26.4: öffentliche Test-Hilfe für Plugins und die neuen Panel-Builder. */
class PanelTestingTest {
    @Test
    void givenPanel_whenRendered_thenDocumentLikeCoreSendsAndFindable() {
        Panel page = Panel.page("Shop").children(
                Ui.text("Hallo"),
                Ui.table("offers").column("name", "Name").column("act", "").row(Ui.row("r1").cell("Schwert").cell(Ui.button("buy").label("Kaufen").onClick(a -> Result.ok()))),
                Ui.list("pick").item(Ui.listItem("a", "A")).item(Ui.listItem("b", "B").selected()).onSelect((a, v) -> Result.ok()),
                Ui.progress(40, 100).rate(-2).id("heat"),
                Ui.form("f", "Verkaufen").confirm("Wirklich?", "Alles weg").onSubmit((a, v) -> Result.ok()),
                Ui.button("settings").label("Einstellungen").openSettings("example:sound"),
                Ui.item("minecraft:stone", null).id("stone"));
        JsonObject doc = PanelTesting.render(page);
        assertEquals(1, doc.get("schema").getAsInt());
        assertEquals(PanelTesting.TEST_PANEL_ID, doc.get("panel").getAsString());
        assertEquals("Kaufen", PanelTesting.plain(PanelTesting.find(doc, "offers/r1/buy").get("label")), "cell elements are prefixed");
        assertEquals("b", PanelTesting.find(doc, "pick").get("selected").getAsString(), "ListItem.selected sets the list's selection");
        assertEquals(-2, PanelTesting.find(doc, "heat").get("rate").getAsDouble());
        assertEquals("Wirklich?", PanelTesting.plain(PanelTesting.find(doc, "f").getAsJsonObject("confirm").get("title")));
        JsonObject nav = PanelTesting.find(doc, "settings").getAsJsonObject("navigate");
        assertEquals("vystorm_core:settings", nav.get("panel").getAsString());
        assertEquals("example:sound", nav.get("arg").getAsString());
        assertEquals("minecraft:stone", PanelTesting.find(doc, "stone").getAsJsonObject("icon").get("item").getAsString());
        assertNull(PanelTesting.find(doc, "nope"));
        List<String> ids = PanelTesting.ids(doc);
        assertTrue(ids.containsAll(List.of("offers", "offers/r1/buy", "pick", "heat", "f", "settings", "~0")), ids.toString());
    }

    @Test
    void givenInvalidPanel_whenRendered_thenIllegalArgumentWithReason() {
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> PanelTesting.render(Panel.page("T").children(Ui.button("x").onClick(a -> Result.ok()), Ui.button("x").onClick(a -> Result.ok()))));
        assertTrue(dup.getMessage().contains("duplicate"), dup.getMessage());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> PanelTesting.render(Panel.page("T").children(Ui.progress(1, 2).color("not-a-color"))));
        assertTrue(bad.getMessage().startsWith("client would reject"), bad.getMessage());
    }

    @Test
    void givenSpec_whenRenderedWithTestContext_thenArgAndKeysVisible() {
        PanelSpec spec = PanelSpec.builder("T").render(ctx -> Panel.page(ctx.text("title.key")).child(Ui.text("arg=" + ctx.arg()).id("a"))).build();
        JsonObject doc = PanelTesting.render(spec, null, "42");
        assertEquals("title.key", PanelTesting.plain(doc.getAsJsonObject("root").get("title")));
        assertEquals("arg=42", PanelTesting.plain(PanelTesting.find(doc, "a").get("text")));
    }

    @Test
    void givenSelectedKeys_whenInvalid_thenRejectedEarly() {
        assertThrows(IllegalArgumentException.class, () -> Ui.list("l").selected("Bad Key!"));
        assertThrows(IllegalArgumentException.class, () -> Ui.table("t").selected("Bad Key!"));
    }
}
