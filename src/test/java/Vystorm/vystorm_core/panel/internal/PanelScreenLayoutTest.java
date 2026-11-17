package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class PanelScreenLayoutTest {
    static final PanelScreenLayout.Texts TEXTS = new PanelScreenLayout.Texts() {
        @Override public Component close() { return Component.text("Close"); }
        @Override public Component page(int page, int pages) { return Component.text(page + "/" + pages); }
        @Override public Component scrollHint() { return Component.text("scroll"); }
    };

    static PanelDocument doc(String children) {
        return PanelSchema.document("{\"schema\":1,\"panel\":\"t:p\",\"root\":{\"type\":\"page\",\"title\":\"Skills\",\"size\":\"large\",\"children\":[" + children + "]}}", Deadline.NONE);
    }

    static PanelScreenLayout.Result layout(String children, PanelScreenLayout.View view) {
        PanelDocument d = doc(children);
        return PanelScreenLayout.layout(d, view, PanelScreenLayout.metrics(d.root(), 2.49, 1.4, 1.0), TEXTS, List.of());
    }

    static <T extends PanelScreenLayout.Act> List<T> acts(PanelScreenLayout.Result r, Class<T> type) {
        return r.acts().values().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void givenSelectableList_whenLaidOut_thenEachRowSendsSelectAndTheChosenRowIsMarked() {
        PanelScreenLayout.Result r = layout("""
                {"type":"list","id":"skills","select":"single","selected":"mining","items":[
                  {"key":"mining","title":"Bergbau","right":"Lv 100","icon":{"item":"minecraft:stone"}},
                  {"key":"wood","title":"Holzfällen","right":"Lv 64"}]}""", new PanelScreenLayout.View());
        List<PanelScreenLayout.Act.Action> selects = acts(r, PanelScreenLayout.Act.Action.class);
        assertEquals(2, selects.size());
        assertTrue(selects.stream().allMatch(a -> a.kind() == PanelLimits.KIND_SELECT && a.elementId().equals("skills")));
        assertEquals(List.of("\"mining\"", "\"wood\""), selects.stream().map(PanelScreenLayout.Act.Action::value).toList());
        assertTrue(r.layout().widgets().stream().anyMatch(w -> w.kind() == ScreenWidget.Kind.FILL && w.fill() == (0xFF000000 | PanelScreenLayout.GOLD)),
                "the chosen row gets a gold bar");
    }

    @Test
    void givenLongContent_whenLaidOut_thenPanelIsCappedAndScrolls() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append(i == 0 ? "" : ",").append("{\"type\":\"text\",\"text\":\"Zeile ").append(i).append("\"}");
        PanelScreenLayout.View view = new PanelScreenLayout.View();
        PanelScreenLayout.Result top = layout(sb.toString(), view);
        assertTrue(top.maxScroll() > 0);
        assertTrue(top.layout().height() <= 1.4 * 0.86 + 1e-9);
        view.scroll = 10;   // beyond the end: clamped
        PanelScreenLayout.Result end = layout(sb.toString(), view);
        assertEquals(end.maxScroll(), view.scroll, 1e-9);
        assertTrue(end.layout().widgets().stream().anyMatch(w -> PanelScreenLayout.plain(asComponent(w.text())).equals("Zeile 59")));
        assertTrue(top.layout().widgets().stream().noneMatch(w -> PanelScreenLayout.plain(asComponent(w.text())).equals("Zeile 59")));
    }

    @Test
    void givenAnyDocument_whenLaidOut_thenAllWidgetsStayInsideThePanel() {
        PanelScreenLayout.Result r = layout("""
                {"type":"section","title":"Werte","variant":"card","children":[
                  {"type":"kv","rows":[{"key":"XP-Bonus","value":"+10 %"},{"key":"Mentor","value":"Esoren"}]},
                  {"type":"progress","value":75,"max":100,"label":"Fortschritt"},
                  {"type":"row","children":[{"type":"button","id":"a","label":"Prestige","style":"primary","action":true},{"type":"button","id":"b","label":"Fähigkeiten","action":true}]}]},
                {"type":"grid","columns":3,"children":[{"type":"badge","text":"A"},{"type":"badge","text":"B"},{"type":"badge","text":"C"},{"type":"badge","text":"D"}]},
                {"type":"table","id":"t","columns":[{"id":"item","label":"Item"},{"id":"price","label":"Preis","align":"end"}],
                  "rows":[{"key":"r1","cells":["Diamant","120"]},{"key":"r2","cells":["Gold","38"]}],"paging":{"page":0,"pages":3}}""", new PanelScreenLayout.View());
        double w = r.layout().width(), h = r.layout().height();
        for (ScreenWidget wd : r.layout().widgets()) {
            assertTrue(wd.u() >= -1e-9 && wd.u() + wd.width() <= w + 1e-9, "x of " + wd);
            assertTrue(wd.v() >= -1e-9 && wd.v() + wd.height() <= h + 1e-9, "y of " + wd);
        }
        assertTrue(acts(r, PanelScreenLayout.Act.Action.class).stream().anyMatch(a -> a.kind() == PanelLimits.KIND_PAGE && a.value().equals("1")));
        assertTrue(acts(r, PanelScreenLayout.Act.Action.class).stream().anyMatch(a -> a.kind() == PanelLimits.KIND_CLICK && a.elementId().equals("a")));
        assertInstanceOf(PanelScreenLayout.Act.Close.class, r.acts().get(PanelScreenLayout.CLOSE));
    }

    @Test
    void givenForm_whenFieldsChangeLocally_thenSubmitCarriesTheEdits() {
        String form = """
                {"type":"form","id":"pay","submit":"Senden","children":[
                  {"type":"toggle","id":"pay.anon","label":"Anonym","value":false},
                  {"type":"number","id":"pay.amount","label":"Betrag","min":0,"max":100,"step":5,"value":10}]}""";
        PanelScreenLayout.View view = new PanelScreenLayout.View();
        PanelScreenLayout.Result r = layout(form, view);
        PanelScreenLayout.Act.FormSet toggle = acts(r, PanelScreenLayout.Act.FormSet.class).stream().filter(f -> f.elementId().equals("pay.anon")).findFirst().orElseThrow();
        assertTrue(toggle.value().getAsBoolean());
        view.form.put(toggle.elementId(), toggle.value());
        view.form.put("pay.amount", JsonParser.parseString("15"));
        PanelScreenLayout.Act.Submit submit = acts(layout(form, view), PanelScreenLayout.Act.Submit.class).getFirst();
        assertEquals("pay", submit.formId());
        assertTrue(submit.values().get("pay.anon").getAsBoolean());
        assertEquals(15, submit.values().get("pay.amount").getAsInt());
    }

    @Test
    void givenTabs_whenLaidOut_thenSelectedTabIsLitAndOnlyItsContentShows() {
        String tabs = "{\"type\":\"tabs\",\"id\":\"m\",\"tabs\":[{\"id\":\"buy\",\"label\":\"Kaufen\",\"children\":[{\"type\":\"text\",\"text\":\"inBuy\"}]},"
                + "{\"id\":\"sell\",\"label\":\"Verkaufen\",\"children\":[{\"type\":\"text\",\"text\":\"inSell\"}]}]}";
        PanelScreenLayout.View view = new PanelScreenLayout.View();
        view.tabs.put("m", "sell");
        PanelScreenLayout.Result r = layout(tabs, view);
        List<String> texts = r.layout().widgets().stream().map(w -> PanelScreenLayout.plain(asComponent(w.text()))).toList();
        assertTrue(texts.contains("inSell"));
        assertFalse(texts.contains("inBuy"));
        assertTrue(r.layout().widgets().stream().anyMatch(w -> w.selected() && PanelScreenLayout.plain(asComponent(w.text())).equals("Verkaufen")));
        assertEquals(Map.of("m", "buy"), Map.of(acts(r, PanelScreenLayout.Act.Tab.class).getFirst().tabsId(), acts(r, PanelScreenLayout.Act.Tab.class).getFirst().tabId()));
    }

    @Test
    void givenDropdownAndInput_whenLaidOut_thenLeftAndRightClickCycleAndInputOpensEditor() {
        PanelScreenLayout.Result r = layout("""
                {"type":"select","id":"sort","label":"Sortieren","options":[{"id":"a","label":"A"},{"id":"b","label":"B"},{"id":"c","label":"C"}],"value":"a"},
                {"type":"input","id":"q","label":"Suche","search":true}""", new PanelScreenLayout.View());
        PanelScreenLayout.Act.Action cycle = acts(r, PanelScreenLayout.Act.Action.class).getFirst();
        assertEquals("\"b\"", cycle.value());
        assertEquals("\"c\"", cycle.alt());
        PanelScreenLayout.Act.Edit edit = acts(r, PanelScreenLayout.Act.Edit.class).getFirst();
        assertTrue(edit.search());
        assertNull(edit.formId());
    }

    private static Component asComponent(Object text) { return text instanceof Component c ? c : Component.text(String.valueOf(text)); }
}
