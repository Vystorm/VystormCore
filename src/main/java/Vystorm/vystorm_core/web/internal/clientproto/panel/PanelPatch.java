// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelPatch.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelJson.*;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Patch-Operationen (Spec §3.6) auf dem JSON-Baum eines Dokuments: {@code set}, {@code replace}, {@code insert},
 * {@code remove}, {@code rows}; {@code graph} erst mit Feature GRAPH. Arbeitet auf einer tiefen Kopie; der Aufrufer
 * prüft das Ergebnis danach vollständig mit {@link PanelSchema}.
 */
public final class PanelPatch {
    /** Patch-ID der Wurzel, wenn sie keine eigene ID hat. */
    public static final String ROOT = "root";

    private PanelPatch() {}

    public record Applied(JsonObject document, int rev, Set<String> touched) {}

    public static JsonObject parse(String json) { return PanelJson.parse(json, MAX_PATCH_BYTES); }

    private record Slot(JsonObject element, JsonArray parentArray, int index) {}

    public static Applied apply(JsonObject document, JsonObject patch, Deadline deadline) {
        int rev = integer(patch, "rev", -1, -1, Integer.MAX_VALUE);
        if (rev < 1) throw invalid("rev missing");
        JsonArray ops = array(patch, "ops", true, MAX_PATCH_OPS);
        JsonObject doc = document.deepCopy();
        Map<String, Slot> index = index(doc);
        Set<String> touched = new LinkedHashSet<>();
        for (JsonElement e : ops) {
            deadline.check();
            JsonObject op = object(e, "ops");
            String kind = str(op, "op", true, 16);
            switch (kind) {
                case "set" -> {
                    String id = str(op, "id", true, 64);
                    Slot s = require(index, id);
                    for (Map.Entry<String, JsonElement> f : object(op.get("fields"), "fields").entrySet()) {
                        if (f.getKey().equals("type") || f.getKey().equals("id")) throw invalid("set cannot change type or id");
                        if (f.getValue().isJsonNull()) s.element().remove(f.getKey());
                        else s.element().add(f.getKey(), f.getValue().deepCopy());
                    }
                    touched.add(id);
                }
                case "replace" -> {
                    String id = str(op, "id", true, 64);
                    Slot s = require(index, id);
                    JsonObject element = object(op.get("element"), "element").deepCopy();
                    String newId = str(element, "id", false, 64);
                    if (newId != null && !newId.equals(id)) throw invalid("replace cannot change id");
                    if (!id.equals(ROOT) || s.parentArray() != null) element.addProperty("id", id);
                    if (s.parentArray() == null) doc.add("root", element);
                    else s.parentArray().set(s.index(), element);
                    index = index(doc);
                    touched.add(id);
                }
                case "insert" -> {
                    String parent = str(op, "parent", true, 64);
                    Slot p = require(index, parent);
                    JsonElement c = p.element().get("children");
                    if (c == null || !c.isJsonArray()) throw invalid("parent has no children");
                    JsonArray children = c.getAsJsonArray();
                    int at = integer(op, "index", children.size(), 0, children.size());
                    JsonArray rebuilt = new JsonArray(children.size() + 1);
                    for (int i = 0; i < children.size(); i++) {
                        if (i == at) rebuilt.add(object(op.get("element"), "element").deepCopy());
                        rebuilt.add(children.get(i));
                    }
                    if (at == children.size()) rebuilt.add(object(op.get("element"), "element").deepCopy());
                    p.element().add("children", rebuilt);
                    index = index(doc);
                    touched.add(parent);
                }
                case "remove" -> {
                    String id = str(op, "id", true, 64);
                    Slot s = require(index, id);
                    if (s.parentArray() == null) throw invalid("root cannot be removed");
                    s.parentArray().remove(s.index());
                    index = index(doc);
                    touched.add(id);
                }
                case "rows" -> {
                    String id = str(op, "id", true, 64);
                    rows(require(index, id).element(), op);
                    index = index(doc);
                    touched.add(id);
                }
                case "graph" -> throw new UiSchema.SchemaException(ERR_UNSUPPORTED, "graph patches need feature GRAPH");
                default -> throw invalid("op unknown");
            }
        }
        if (doc.toString().length() > MAX_DOCUMENT_BYTES) throw limit("document too large after patch");
        return new Applied(doc, rev, touched);
    }

    private static void rows(JsonObject element, JsonObject op) {
        String type = str(element, "type", true, 24);
        String field = switch (type) {
            case "table" -> "rows";
            case "list" -> "items";
            default -> throw invalid("rows needs a table or list");
        };
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        for (JsonElement r : array(element, field, false, MAX_TABLE_ROWS + MAX_PATCH_OPS)) {
            JsonObject row = object(r, field);
            byKey.put(str(row, "key", true, 64), row);
        }
        for (JsonElement k : array(op, "remove", false, MAX_TABLE_ROWS)) {
            String key = k.isJsonPrimitive() ? k.getAsString() : "";
            if (byKey.remove(key) == null) throw new UiSchema.SchemaException(ERR_UNKNOWN_ID, "unknown row key");
        }
        for (JsonElement u : array(op, "upsert", false, MAX_TABLE_ROWS)) {
            JsonObject row = object(u, "upsert").deepCopy();
            String key = str(row, "key", true, 64);
            if (!PanelIds.rowKey(key)) throw invalid("row key invalid");
            byKey.put(key, row);   // bestehende Schlüssel behalten ihre Position
        }
        if (op.has("order")) {
            JsonArray order = array(op, "order", true, MAX_TABLE_ROWS);
            if (order.size() != byKey.size()) throw invalid("order must list every key");
            Map<String, JsonObject> ordered = new LinkedHashMap<>();
            for (JsonElement k : order) {
                String key = k.isJsonPrimitive() ? k.getAsString() : "";
                JsonObject row = byKey.get(key);
                if (row == null || ordered.put(key, row) != null) throw invalid("order must list every key once");
            }
            byKey = ordered;
        }
        JsonArray out = new JsonArray(byKey.size());
        byKey.values().forEach(out::add);
        element.add(field, out);
    }

    private static Slot require(Map<String, Slot> index, String id) {
        Slot s = index.get(id);
        if (s == null) throw new UiSchema.SchemaException(ERR_UNKNOWN_ID, "unknown id");
        return s;
    }

    /** ID → Fundstelle; durchsucht children, footer, tabs[].children und Tabellenzellen. */
    private static Map<String, Slot> index(JsonObject doc) {
        Map<String, Slot> out = new HashMap<>();
        JsonObject root = object(doc.get("root"), "root");
        String rootId = str(root, "id", false, 64);
        out.put(rootId == null ? ROOT : rootId, new Slot(root, null, -1));
        walk(root, out, 0);
        return out;
    }

    private static void walk(JsonObject element, Map<String, Slot> out, int depth) {
        if (depth > MAX_DEPTH + 2) throw limit("elements nested too deeply");
        for (String f : List.of("children", "footer")) walkArray(element.get(f), out, depth);
        JsonElement tabs = element.get("tabs");
        if (tabs != null && tabs.isJsonArray()) for (JsonElement t : tabs.getAsJsonArray())
            if (t.isJsonObject()) walkArray(t.getAsJsonObject().get("children"), out, depth);
        JsonElement rows = element.get("rows");
        if (rows != null && rows.isJsonArray()) for (JsonElement r : rows.getAsJsonArray())
            if (r.isJsonObject()) walkArray(r.getAsJsonObject().get("cells"), out, depth);
    }

    private static void walkArray(JsonElement array, Map<String, Slot> out, int depth) {
        if (array == null || !array.isJsonArray()) return;
        JsonArray a = array.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).isJsonObject()) continue;
            JsonObject child = a.get(i).getAsJsonObject();
            if (!child.has("type")) continue;   // Textzelle
            JsonElement id = child.get("id");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) out.put(id.getAsString(), new Slot(child, a, i));
            walk(child, out, depth + 1);
        }
    }
}
