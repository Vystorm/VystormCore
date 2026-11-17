package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import org.junit.jupiter.api.Test;

class DialogDowngradeTest {
    static PanelDocument doc(String children) {
        return PanelSchema.document("{\"schema\":1,\"panel\":\"t:p\",\"root\":{\"type\":\"page\",\"title\":\"T\",\"children\":[" + children + "]}}", Deadline.NONE);
    }

    static String rows(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : ",").append("{\"key\":\"r").append(i).append("\",\"cells\":[\"Zeile ").append(i).append("\",\"x\"]}");
        return sb.toString();
    }

    @Test
    void controlsBecomeDialogInputsAndButtons() {
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc("""
                {"type":"heading","text":"Anzeige"},
                {"type":"toggle","id":"hud","label":"HUD","value":true},
                {"type":"slider","id":"range","label":"R","min":4,"max":64,"step":4,"value":16},
                {"type":"select","id":"mode","label":"M","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"value":"b"},
                {"type":"input","id":"name","label":"N","value":"x","maxLength":16},
                {"type":"item","icon":{"item":"minecraft:diamond","count":3}},
                {"type":"button","id":"go","label":"Los","action":true}"""), null, 0, false);
        assertTrue(p.capable());
        assertEquals(java.util.List.of(DialogDowngrade.Input.Kind.BOOL, DialogDowngrade.Input.Kind.RANGE, DialogDowngrade.Input.Kind.OPTIONS,
                DialogDowngrade.Input.Kind.TEXT), p.inputs().stream().map(DialogDowngrade.Input::kind).toList());
        assertEquals(16.0, p.inputs().get(1).initial());
        assertInstanceOf(DialogDowngrade.ItemBody.class, p.body().get(1));
        assertTrue(p.buttons().stream().anyMatch(b -> b.kind() == DialogDowngrade.ButtonKind.APPLY), "inputs need an apply button");
        assertTrue(p.buttons().stream().anyMatch(b -> b.elementId().equals("go") && b.kind() == DialogDowngrade.ButtonKind.CLICK));
    }

    @Test
    void bigTablesNeedAWebLink() {
        String table = "{\"type\":\"table\",\"id\":\"t\",\"columns\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}],\"rows\":[" + rows(30) + "]}";
        assertFalse(DialogDowngrade.plan(doc(table), null, 0, false).capable());
        DialogDowngrade.Plan withWeb = DialogDowngrade.plan(doc(table), null, 0, true);
        assertTrue(withWeb.capable());
        assertEquals(21, withWeb.body().size(), "20 rows + 'and 10 more' line");
        assertTrue(withWeb.buttons().stream().anyMatch(b -> b.kind() == DialogDowngrade.ButtonKind.WEB));
        assertTrue(DialogDowngrade.plan(doc(table.replace(rows(30), rows(5))), null, 0, false).capable());
    }

    @Test
    void imagesAndUnsupportedAreNotDialogCapable() {
        assertFalse(DialogDowngrade.plan(doc("{\"type\":\"image\",\"src\":{\"sprite\":\"minecraft:hud/heart/full\"}}"), null, 0, false).capable());
        assertFalse(DialogDowngrade.plan(doc("{\"type\":\"chart\",\"id\":\"c\"}"), null, 0, false).capable());
    }

    @Test
    void tabsShowSelectedAndOfferOthers() {
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc("{\"type\":\"tabs\",\"id\":\"t\",\"tabs\":[{\"id\":\"a\",\"label\":\"A\",\"children\":[{\"type\":\"text\",\"text\":\"inA\"}]},"
                + "{\"id\":\"b\",\"label\":\"B\",\"children\":[{\"type\":\"text\",\"text\":\"inB\"}]}]}"), java.util.Map.of("t", "b"), 0, false);
        assertEquals("inB", ((DialogDowngrade.TextBody) p.body().getFirst()).text().plainText());
        assertTrue(p.buttons().stream().anyMatch(b -> b.kind() == DialogDowngrade.ButtonKind.TAB && b.target().equals("a")));
    }

    @Test
    void formBecomesInputsWithSubmit() {
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc("{\"type\":\"form\",\"id\":\"pay\",\"submit\":\"Senden\",\"children\":[{\"type\":\"input\",\"id\":\"pay.amount\",\"label\":\"B\",\"chars\":\"integer\"}]}"),
                null, 0, false);
        assertEquals("pay", p.inputs().getFirst().formId());
        assertTrue(p.buttons().stream().anyMatch(b -> b.kind() == DialogDowngrade.ButtonKind.SUBMIT && b.elementId().equals("pay")));
    }

    @Test
    void manyButtonsArePaged() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 30; i++) b.append(i == 0 ? "" : ",").append("{\"type\":\"button\",\"id\":\"b").append(i).append("\",\"label\":\"").append(i).append("\",\"action\":true}");
        DialogDowngrade.Plan first = DialogDowngrade.plan(doc(b.toString()), null, 0, false);
        assertEquals(3, first.pages());
        assertEquals(12, first.buttons().stream().filter(x -> x.kind() == DialogDowngrade.ButtonKind.CLICK).count());
        assertTrue(first.buttons().stream().anyMatch(x -> x.kind() == DialogDowngrade.ButtonKind.PAGE && x.target().equals("1")));
    }

    /** Final Review Important 8: {@code confirm} geht im Dialog nicht verloren. */
    @Test
    void confirmButtonCarriesItsConfirmation() {
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc("{\"type\":\"button\",\"id\":\"rm\",\"label\":\"Löschen\",\"style\":\"danger\",\"action\":true,"
                + "\"confirm\":{\"title\":\"Wirklich?\",\"text\":\"Weg ist weg\"}},{\"type\":\"button\",\"id\":\"ok\",\"label\":\"OK\",\"action\":true}"), null, 0, false);
        DialogDowngrade.Button rm = p.buttons().stream().filter(b -> b.elementId().equals("rm")).findFirst().orElseThrow();
        assertNotNull(rm.confirm());
        assertEquals("Wirklich?", rm.confirm().title().plainText());
        assertNull(p.buttons().stream().filter(b -> b.elementId().equals("ok")).findFirst().orElseThrow().confirm());
    }

    /** Final Review Important 9 (Dialog): gesperrte Eingaben sind keine Dialog-Eingaben, sondern Text. */
    @Test
    void disabledInputsAreShownAsText() {
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc("{\"type\":\"input\",\"id\":\"n\",\"label\":\"Name\",\"value\":\"fest\",\"disabled\":true},"
                + "{\"type\":\"toggle\",\"id\":\"t\",\"label\":\"T\"}"), null, 0, false);
        assertEquals(java.util.List.of("t"), p.inputs().stream().map(DialogDowngrade.Input::elementId).toList());
        assertTrue(p.body().stream().anyMatch(b -> b instanceof DialogDowngrade.TextBody t && t.text().plainText().contains("fest")));
    }

    /** Minor: jeder Reiter-Block merkt sich seine eigene Auswahl; Reiter-Knöpfe nennen ihren Block und ob er lazy ist. */
    @Test
    void tabSelectionIsKeptPerTabsElement() {
        String tabs = "{\"type\":\"tabs\",\"id\":\"x\",\"tabs\":[{\"id\":\"a\",\"label\":\"A\",\"children\":[{\"type\":\"text\",\"text\":\"xa\"}]},{\"id\":\"b\",\"label\":\"B\",\"lazy\":true}]},"
                + "{\"type\":\"tabs\",\"id\":\"y\",\"tabs\":[{\"id\":\"a\",\"label\":\"A\",\"children\":[{\"type\":\"text\",\"text\":\"ya\"}]},{\"id\":\"b\",\"label\":\"B\",\"children\":[{\"type\":\"text\",\"text\":\"yb\"}]}]}";
        DialogDowngrade.Plan p = DialogDowngrade.plan(doc(tabs), java.util.Map.of("y", "b"), 0, false);
        assertEquals(java.util.List.of("xa", "yb"), p.body().stream().map(b -> ((DialogDowngrade.TextBody) b).text().plainText()).toList());
        DialogDowngrade.Button lazy = p.buttons().stream().filter(b -> b.kind() == DialogDowngrade.ButtonKind.TAB && b.elementId().equals("x")).findFirst().orElseThrow();
        assertEquals("b", lazy.target());
        assertTrue(lazy.lazy());
    }
}
