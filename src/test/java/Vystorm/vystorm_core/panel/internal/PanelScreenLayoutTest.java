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

    // ------------------------------------------------------------------ list layout (0.66.0)

    static final String CONTROLS = """
            {"type":"toggle","id":"hud","label":"Show the HUD","description":"The corner window with XP","value":true},
            {"type":"slider","id":"range","label":"Range","min":4,"max":64,"step":4,"value":16,"tooltip":["How far you see others"]},
            {"type":"select","id":"mode","label":"Mode","options":[{"id":"a","label":"Calm"},{"id":"b","label":"Busy"},{"id":"c","label":"Loud"}],"value":"a"},
            {"type":"number","id":"big","label":"Budget","min":0,"max":100000,"step":1,"value":250},
            {"type":"input","id":"name","label":"Name","value":"Esoren"}""";

    static PanelScreenLayout.Result listLayout(String sectionFields, String children, PanelScreenLayout.View view, double height) {
        String json = "{\"schema\":1,\"panel\":\"t:p\",\"root\":{\"type\":\"page\",\"title\":\"Settings\",\"size\":\"large\",\"children\":["
                + "{\"type\":\"section\",\"id\":\"sec\",\"title\":\"Display\"" + sectionFields + ",\"children\":[" + children + "]}]}}";
        com.google.gson.JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        PanelDocument d = PanelSchema.document(o, Deadline.NONE);
        return PanelScreenLayout.layout(d, view, PanelScreenLayout.metrics(d.root(), 2.49, height, 1.0), TEXTS, List.of(), PanelScreenLayout.listIds(o));
    }

    static List<PanelScreenLayout.Act> actsOf(PanelScreenLayout.Result r) {
        return r.acts().entrySet().stream().filter(e -> !e.getKey().equals(PanelScreenLayout.CLOSE)).map(Map.Entry::getValue).toList();
    }

    @Test
    void givenListSection_whenLaidOut_thenEachControlIsOneRowWithNameValueAndDescription() {
        PanelScreenLayout.Result r = listLayout(",\"layout\":\"list\"", CONTROLS, new PanelScreenLayout.View(), 1.4);
        List<String> texts = r.layout().widgets().stream().map(w -> PanelScreenLayout.plain(asComponent(w.text()))).toList();
        for (String s : List.of("Display", "Show the HUD", "ON", "The corner window with XP", "Range", "16", "How far you see others", "Mode", "Calm", "Budget", "250", "Name", "Esoren"))
            assertTrue(texts.contains(s), "shows " + s + " in " + texts);
        assertFalse(texts.contains("Calm  ◀ ▶"), "no tile-style select button");
        // one clickable row per control, each spanning the section width
        List<ScreenWidget> rows = r.layout().widgets().stream().filter(w -> r.acts().containsKey(w.id()) && !w.id().equals(PanelScreenLayout.CLOSE)).toList();
        assertEquals(5, rows.size());
        double width = rows.getFirst().width();
        assertTrue(rows.stream().allMatch(w -> Math.abs(w.width() - width) < 1e-9 && w.kind() == ScreenWidget.Kind.BUTTON));
        for (ScreenWidget row : rows) {
            ScreenWidget value = r.layout().widgets().stream().filter(w -> w.id().equals(row.id() + ".v")).findFirst().orElseThrow();
            assertEquals(ScreenWidget.Align.END, value.align());
            assertTrue(value.u() + value.width() <= row.u() + row.width() + 1e-9 && value.u() > row.u() + row.width() / 2, "value on the right of " + row.id());
        }
    }

    @Test
    void givenListSection_whenRowsClicked_thenTheyMeanWhatTheControlsMeant() {
        PanelScreenLayout.Result list = listLayout(",\"layout\":\"list\"", CONTROLS, new PanelScreenLayout.View(), 1.4);
        PanelScreenLayout.Result plain = listLayout("", CONTROLS, new PanelScreenLayout.View(), 1.4);
        // the same element ids, kinds and values as the normal controls (toggle flips, slider steps, select cycles)
        List<PanelScreenLayout.Act.Action> before = acts(plain, PanelScreenLayout.Act.Action.class);
        List<PanelScreenLayout.Act.Action> after = acts(list, PanelScreenLayout.Act.Action.class);
        PanelScreenLayout.Act.Action toggle = after.stream().filter(a -> a.elementId().equals("hud")).findFirst().orElseThrow();
        assertEquals(before.stream().filter(a -> a.elementId().equals("hud")).findFirst().orElseThrow(), toggle);
        assertEquals("false", toggle.value());
        PanelScreenLayout.Act.Action select = after.stream().filter(a -> a.elementId().equals("mode")).findFirst().orElseThrow();
        assertEquals(before.stream().filter(a -> a.elementId().equals("mode")).findFirst().orElseThrow(), select);
        assertEquals("\"b\"", select.value());
        assertEquals("\"c\"", select.alt(), "right click goes back");
        PanelScreenLayout.Act.Action slider = after.stream().filter(a -> a.elementId().equals("range")).findFirst().orElseThrow();
        assertEquals(PanelLimits.KIND_CHANGE, slider.kind());
        assertEquals("20", slider.value(), "left click: one step up, like the + button");
        assertEquals("12", slider.alt(), "right click: one step down");
        assertTrue(before.stream().anyMatch(a -> a.elementId().equals("range") && a.value().equals("20")), "the + button sent the same value");
        PanelScreenLayout.Act.Edit number = acts(list, PanelScreenLayout.Act.Edit.class).stream().filter(e -> e.elementId().equals("big")).findFirst().orElseThrow();
        assertTrue(number.number(), "wide number fields open the typing dialog and send a number");
        PanelScreenLayout.Act.Edit name = acts(list, PanelScreenLayout.Act.Edit.class).stream().filter(e -> e.elementId().equals("name")).findFirst().orElseThrow();
        assertFalse(name.number());
        assertEquals("Esoren", name.value());
        assertEquals(5, actsOf(list).size(), "one act per row, nothing else clickable");
    }

    @Test
    void givenListSectionInAForm_whenRowsClicked_thenFieldsChangeLocally() {
        PanelScreenLayout.View view = new PanelScreenLayout.View();
        String json = "{\"schema\":1,\"panel\":\"t:p\",\"root\":{\"type\":\"page\",\"title\":\"S\",\"children\":[{\"type\":\"form\",\"id\":\"f\",\"submit\":\"Save\",\"children\":["
                + "{\"type\":\"section\",\"id\":\"sec\",\"title\":\"Display\",\"layout\":\"list\",\"children\":[" + CONTROLS + "]}]}]}}";
        com.google.gson.JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        PanelDocument d = PanelSchema.document(o, Deadline.NONE);
        PanelScreenLayout.Result r = PanelScreenLayout.layout(d, view, PanelScreenLayout.metrics(d.root(), 2.49, 1.4, 1.0), TEXTS, List.of(), PanelScreenLayout.listIds(o));
        List<PanelScreenLayout.Act.FormSet> sets = acts(r, PanelScreenLayout.Act.FormSet.class);
        assertTrue(sets.stream().anyMatch(s -> s.elementId().equals("hud") && !s.value().getAsBoolean()));
        assertTrue(sets.stream().anyMatch(s -> s.elementId().equals("mode") && s.value().getAsString().equals("b") && s.alt().getAsString().equals("c")));
        assertEquals(1, acts(r, PanelScreenLayout.Act.Submit.class).size());
    }

    @Test
    void givenLongList_whenScrolled_thenTheHintTellsWhichRowsAreShown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 27; i++) sb.append(i == 0 ? "" : ",").append("{\"type\":\"toggle\",\"id\":\"t").append(i).append("\",\"label\":\"Setting number ").append(i + 1).append("\",\"value\":false}");
        PanelScreenLayout.View view = new PanelScreenLayout.View();
        PanelScreenLayout.Result top = listLayout(",\"layout\":\"list\"", sb.toString(), view, 1.0);
        assertTrue(top.maxScroll() > 0);
        String hint = PanelScreenLayout.plain(top.hint());
        assertTrue(hint.startsWith("scroll") && hint.contains("1–") && hint.endsWith("/ 27"), hint);
        view.scroll = top.maxScroll();
        PanelScreenLayout.Result end = listLayout(",\"layout\":\"list\"", sb.toString(), view, 1.0);
        assertTrue(PanelScreenLayout.plain(end.hint()).endsWith("–27 / 27"), PanelScreenLayout.plain(end.hint()));
        // a row is shown with all its parts or not at all
        for (ScreenWidget w : end.layout().widgets()) {
            int dot = w.id().indexOf('.');
            if (dot > 0) assertTrue(end.layout().widgets().stream().anyMatch(o -> o.id().equals(w.id().substring(0, dot))), "part without its row: " + w.id());
        }
    }

    @Test
    void givenWrittenDocument_whenListIdsCollected_thenOnlyMarkedElements() {
        com.google.gson.JsonObject o = JsonParser.parseString("""
                {"root":{"type":"page","children":[{"type":"section","id":"a","layout":"list"},{"type":"section","id":"b"},
                 {"type":"tabs","id":"t","tabs":[{"id":"x","children":[{"type":"section","id":"c","layout":"list"}]}]}]}}""").getAsJsonObject();
        assertEquals(java.util.Set.of("a", "c"), PanelScreenLayout.listIds(o));
        assertTrue(PanelScreenLayout.listIds(null).isEmpty());
    }

    private static Component asComponent(Object text) { return text instanceof Component c ? c : Component.text(String.valueOf(text)); }
}
