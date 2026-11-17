package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.web.internal.clientproto.panel.ActionCheck;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;

/**
 * {@link Panel} → Dokument-JSON (Schema v1) + Handler-Tabelle nach Element-ID. Vergibt Pfad-IDs ({@code ~1/0}) für
 * namenlose Elemente im Baum, präfixt Elemente in Tabellenzellen mit {@code <tabelle>/<zeile>/}, lehnt doppelte IDs und
 * interaktive Elemente ohne ID ab. Aktionen tragen nur IDs; welches Objekt gemeint ist, steckt in der Closure
 * (Spec §2.4 Punkt 4). Tabellen-Aktionen liegen unter {@code <id>#page}, {@code <id>#sort}, {@code <id>#select}. Core-intern.
 */
public final class PanelWriter {
    private static final UiBridge UI = UiBridges.BRIDGE;

    /** Handler mit bereits geprüftem Wert ({@link ActionCheck}). */
    @FunctionalInterface
    public interface Handler { Result handle(PanelAction action, Object checkedValue); }

    public record Written(JsonObject document, Map<String, Handler> handlers) {}

    private final Player viewer;
    private final String namespace;
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Set<String> ids = new HashSet<>();

    private PanelWriter(Player viewer, String namespace) {
        this.viewer = viewer;
        this.namespace = namespace;
    }

    public static Written write(String panelId, Panel panel, Player viewer, String webModule, String webPage) {
        if (!PanelIds.panel(panelId)) throw new IllegalArgumentException("invalid panel id: " + panelId);
        PanelWriter w = new PanelWriter(viewer, panelId.substring(0, panelId.indexOf(':')));
        JsonObject root = new JsonObject();
        root.addProperty("type", "page");
        root.add("title", w.text(UI.title(panel)));
        if (UI.subtitle(panel) != null) root.add("subtitle", w.text(UI.subtitle(panel)));
        if (UI.icon(panel) != null) root.add("icon", w.icon(UI.icon(panel)));
        root.addProperty("size", UI.size(panel).name().toLowerCase(Locale.ROOT));
        if (UI.theme(panel) != null) root.addProperty("theme", UI.theme(panel).name().toLowerCase(Locale.ROOT));
        if (UI.accent(panel) != null) root.addProperty("accent", UI.accent(panel));
        if (webModule != null) {
            JsonObject web = new JsonObject();
            web.addProperty("module", webModule);
            web.addProperty("page", webPage == null ? "" : webPage);
            root.add("web", web);
        }
        root.add("children", w.children(UI.children(panel), "~", null));
        root.add("footer", w.children(UI.footer(panel), null, null));
        JsonObject doc = new JsonObject();
        doc.addProperty("schema", 1);
        doc.addProperty("panel", panelId);
        doc.add("root", root);
        return new Written(doc, Map.copyOf(w.handlers));
    }

    /** @param path Pfad des Elternteils für Pfad-IDs ({@code "~"} = Wurzel), null = keine Pfad-IDs (Fußleiste, Zellen) */
    private JsonArray children(List<? extends Ui.Element<?>> elements, String path, String cellPrefix) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < elements.size(); i++)
            out.add(element(elements.get(i), path == null ? null : path.equals("~") ? "~" + i : path + "/" + i, cellPrefix));
        return out;
    }

    private JsonObject element(Ui.Element<?> e, String path, String cellPrefix) {
        JsonObject o = new JsonObject();
        o.addProperty("type", UI.type(e));
        String id = UI.id(e);
        if (id != null && cellPrefix != null) id = cellPrefix + id;
        if (id == null) id = path;   // Pfad-ID, sonst namenlos
        if (id == null && UI.handler(e) != null) throw new IllegalStateException("interactive panel element " + UI.type(e) + " needs an id");
        if (id != null) {
            if (!(PanelIds.element(id) || PanelIds.auto(id))) throw new IllegalArgumentException("panel element id too long or invalid: " + id);
            if (!ids.add(id)) throw new IllegalStateException("duplicate panel element id: " + id);
            o.addProperty("id", id);
        }
        for (Map.Entry<String, Object> f : UI.fields(e).entrySet()) o.add(f.getKey(), value(f.getValue()));
        if (!UI.tooltip(e).isEmpty()) o.add("tooltip", texts(UI.tooltip(e)));
        switch (e) {
            case Ui.ItemIcon item -> o.add("icon", icon(item));
            case Ui.Button b when UI.buttonIcon(b) != null -> o.add("icon", icon(UI.buttonIcon(b)));
            case Ui.Tabs tabs -> o.add("tabs", tabs(tabs, path));
            case Ui.Kv kv -> o.add("rows", kvRows(kv));
            case Ui.Table table -> table(table, id, o);
            case Ui.ListView list -> {
                o.add("items", listItems(list));
                if (!o.has("selected")) for (Ui.ListItem it : UI.items(list)) if (UI.selected(it)) o.addProperty("selected", UI.key(it));
            }
            case Ui.Select select -> o.add("options", options(select));
            default -> { }
        }
        if (!UI.children(e).isEmpty()) o.add("children", children(UI.children(e), cellPrefix == null ? path : null, cellPrefix));
        bind(e, id);
        return o;
    }

    @SuppressWarnings("unchecked")
    private void bind(Ui.Element<?> e, String id) {
        Object h = UI.handler(e);
        if (h == null) return;
        Handler bound = switch (h) {
            case Ui.Click c -> (a, v) -> c.handle(a);
            case Ui.Change<?> c -> (a, v) -> ((Ui.Change<Object>) c).handle(a, v);
            default -> throw new IllegalStateException("unknown handler type");
        };
        handlers.put(id, bound);
    }

    private void table(Ui.Table t, String id, JsonObject o) {
        JsonArray cols = new JsonArray();
        for (Map<String, Object> c : UI.columns(t)) {
            JsonObject col = new JsonObject();
            for (Map.Entry<String, Object> f : c.entrySet()) col.add(f.getKey(), value(f.getValue()));
            cols.add(col);
        }
        o.add("columns", cols);
        JsonArray rows = new JsonArray();
        for (Ui.TableRow r : UI.rows(t)) {
            JsonObject row = new JsonObject();
            row.addProperty("key", UI.key(r));
            JsonArray cells = new JsonArray();
            for (Object cell : UI.cells(r)) {
                if (cell instanceof Ui.Cell c) cells.add(cell(c));
                else cells.add(element((Ui.Element<?>) cell, null, id + "/" + UI.key(r) + "/"));
            }
            row.add("cells", cells);
            if (!UI.rowTooltip(r).isEmpty()) row.add("tooltip", texts(UI.rowTooltip(r)));
            rows.add(row);
        }
        o.add("rows", rows);
        if (UI.onPage(t) != null) handlers.put(id + "#page", (a, v) -> UI.onPage(t).handle(a, (Integer) v));
        if (UI.onSort(t) != null) handlers.put(id + "#sort", (a, v) -> {
            ActionCheck.SortValue s = (ActionCheck.SortValue) v;
            return UI.onSort(t).handle(a, new Ui.Sort(s.column(), s.descending()));
        });
        if (UI.onSelect(t) != null) handlers.put(id + "#select", (a, v) -> UI.onSelect(t).handle(a, (String) v));
    }

    /** Textzelle; mit {@code sort} als Objekt {@code {text, sort}} (Text dann einfach). */
    private JsonElement cell(Ui.Cell c) {
        JsonElement text = text(UI.cellText(c));
        if (UI.cellSort(c) == null) return text;
        JsonObject withSort = new JsonObject();
        withSort.addProperty("text", plain(text));
        withSort.addProperty("sort", UI.cellSort(c));
        return withSort;
    }

    private JsonArray tabs(Ui.Tabs t, String path) {
        JsonArray out = new JsonArray();
        int i = 0;
        for (Object[] tab : UI.tabList(t)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", (String) tab[0]);
            o.add("label", text(tab[1]));
            if ((Boolean) tab[2]) o.addProperty("lazy", true);
            @SuppressWarnings("unchecked") List<Ui.Element<?>> children = (List<Ui.Element<?>>) tab[3];
            o.add("children", children(children, path == null ? null : path + "/" + i, null));
            i++;
            out.add(o);
        }
        return out;
    }

    private JsonArray kvRows(Ui.Kv kv) {
        JsonArray out = new JsonArray();
        for (Object[] r : UI.kvRows(kv)) {
            JsonObject o = new JsonObject();
            o.add("key", text(r[0]));
            o.add("value", text(r[1]));
            out.add(o);
        }
        return out;
    }

    private JsonArray listItems(Ui.ListView l) {
        JsonArray out = new JsonArray();
        for (Ui.ListItem it : UI.items(l)) {
            JsonObject o = new JsonObject();
            o.addProperty("key", UI.key(it));
            o.add("title", text(UI.title(it)));
            if (UI.subtitle(it) != null) o.add("subtitle", text(UI.subtitle(it)));
            if (UI.right(it) != null) o.add("right", text(UI.right(it)));
            if (UI.badge(it) != null) o.add("badge", text(UI.badge(it)));
            if (UI.icon(it) != null) o.add("icon", icon(UI.icon(it)));
            out.add(o);
        }
        return out;
    }

    private JsonArray options(Ui.Select s) {
        JsonArray out = new JsonArray();
        for (Object[] op : UI.options(s)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", (String) op[0]);
            o.add("label", text(op[1]));
            if (op[2] != null) o.add("icon", icon((Ui.Icon) op[2]));
            out.add(o);
        }
        return out;
    }

    private JsonObject icon(Ui.Icon icon) {
        JsonObject o = new JsonObject();
        switch (icon) {
            case Ui.ItemIcon i -> {
                o.addProperty("item", UI.item(i));
                if (UI.model(i) != null) o.addProperty("model", UI.model(i));
                if (UI.count(i) > 1) o.addProperty("count", UI.count(i));
                if (UI.glint(i)) o.addProperty("glint", true);
            }
            case Ui.SpriteIcon s -> o.addProperty("sprite", s.sprite());
            case Ui.ImageIcon img -> o.addProperty("image", img.webPath());
        }
        return o;
    }

    /** Feldwerte: Text → UiText-JSON, Navigation → {panel, arg} mit Namensraum, Map rekursiv, sonst Primitive. */
    private JsonElement value(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (UI.isTextRef(v)) return text(UI.textRef(v));
        if (UI.isTextList(v)) return texts(UI.textList(v));
        if (UI.isNav(v)) {
            JsonObject n = new JsonObject();
            String panel = UI.navPanel(v);
            n.addProperty("panel", panel.contains(":") ? panel : namespace + ":" + panel);
            n.addProperty("arg", UI.navArg(v));
            return n;
        }
        return switch (v) {
            case JsonElement j -> j;
            case Boolean b -> new JsonPrimitive(b);
            case Number n -> new JsonPrimitive(n);
            case String s -> new JsonPrimitive(s);
            case Map<?, ?> m -> {
                JsonObject o = new JsonObject();
                for (Map.Entry<?, ?> e : m.entrySet()) o.add(e.getKey().toString(), value(e.getValue()));
                yield o;
            }
            default -> throw new IllegalArgumentException("unsupported panel field value " + v.getClass().getSimpleName());
        };
    }

    private JsonElement text(Object t) { return PanelText.of(viewer, t); }

    private JsonArray texts(List<Object> lines) {
        JsonArray a = new JsonArray();
        for (Object line : lines) a.add(text(line));
        return a;
    }

    private static String plain(JsonElement text) {
        if (text.isJsonPrimitive()) return text.getAsString();
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : text.getAsJsonArray()) sb.append(e.isJsonObject() ? e.getAsJsonObject().get("text").getAsString() : e.getAsString());
        return sb.toString();
    }
}
