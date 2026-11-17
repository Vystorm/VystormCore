package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelPatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Alter und neuer Baum nach ID (Core vergibt allen Elementen im Baum IDs) → Patch-Operationen (Spec §5): {@code set}
 * für geänderte oberste Felder, {@code rows} für Tabellen/Listen, {@code replace}, wenn sich die Kinderliste eines
 * Elements ändert. Ändert sich die Wurzel-Kinderliste oder wird der Patch größer als das halbe Dokument → {@code null}
 * (Core schickt ein OPEN mit „Zustand behalten“). Core-intern.
 */
public final class PanelDiff {
    private static final Set<String> STRUCTURE = Set.of("children", "footer", "tabs", "rows", "items", "id", "type");

    private PanelDiff() {}

    public static JsonObject diff(JsonObject oldDoc, JsonObject newDoc, int rev) {
        if (!Objects.equals(oldDoc.get("panel"), newDoc.get("panel"))) return null;
        JsonArray ops = new JsonArray();
        if (!element(oldDoc.getAsJsonObject("root"), newDoc.getAsJsonObject("root"), PanelPatch.ROOT, ops, true)) return null;
        JsonObject patch = new JsonObject();
        patch.addProperty("rev", rev);
        patch.add("ops", ops);
        // Größenvergleich ohne Serialisieren (Final Review Important 6): geschätzte Längen genügen für die Faustregel.
        if (ops.size() > PanelLimits.MAX_PATCH_OPS || estimate(patch) > Math.max(512, estimate(newDoc) / 2)) return null;
        return patch;
    }

    /**
     * Ungefähre Länge des kompakten JSON-Texts (Zeichen) ohne zu serialisieren: Strings + Anführungszeichen, Zahlen und
     * Literale, Schlüssel, Kommas, Klammern. Escapes werden nicht mitgezählt (Unterschätzung um wenige Prozent).
     */
    public static int estimate(JsonElement e) {
        if (e == null || e.isJsonNull()) return 4;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString().length() + 2;
            if (p.isBoolean()) return p.getAsBoolean() ? 4 : 5;
            return p.getAsString().length();
        }
        int n = 2;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) n += estimate(x) + 1;
            return n;
        }
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) n += x.getKey().length() + 4 + estimate(x.getValue());
        return n;
    }

    /** @return false, wenn nur ein Ersetzen der Wurzel hilft */
    private static boolean element(JsonObject a, JsonObject b, String id, JsonArray ops, boolean root) {
        if (!Objects.equals(a.get("type"), b.get("type"))) return replace(id, b, ops, root);
        JsonObject set = new JsonObject();
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            if (STRUCTURE.contains(k)) continue;
            JsonElement av = a.get(k), bv = b.get(k);
            if (!Objects.equals(av, bv)) set.add(k, bv == null ? JsonNull.INSTANCE : bv.deepCopy());
        }
        String type = b.get("type").getAsString();
        boolean ok = true;
        if (type.equals("table") || type.equals("list")) rows(a, b, type.equals("table") ? "rows" : "items", id, ops);
        if (!sameTabs(a.get("tabs"), b.get("tabs"))) ok = false;
        List<JsonArray> childOps = new ArrayList<>();
        for (String f : List.of("children", "footer")) {
            JsonArray sub = new JsonArray();
            if (!children(a.get(f), b.get(f), sub)) ok = false;
            childOps.add(sub);
        }
        JsonArray tabOps = new JsonArray();
        if (ok && b.has("tabs")) {
            JsonArray at = a.getAsJsonArray("tabs"), bt = b.getAsJsonArray("tabs");
            for (int i = 0; i < bt.size() && ok; i++)
                ok = children(at.get(i).getAsJsonObject().get("children"), bt.get(i).getAsJsonObject().get("children"), tabOps);
        }
        if (!ok) return replace(id, b, ops, root);
        if (set.size() > 0) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set");
            op.addProperty("id", id);
            op.add("fields", set);
            ops.add(op);
        }
        for (JsonArray sub : childOps) ops.addAll(sub);
        ops.addAll(tabOps);
        return true;
    }

    /** Gleiche ID-Folge → paarweise vergleichen; sonst false (Elternteil ersetzen). */
    private static boolean children(JsonElement a, JsonElement b, JsonArray ops) {
        if (a == null && b == null) return true;
        if (a == null || b == null || !a.isJsonArray() || !b.isJsonArray()) return false;
        JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            JsonObject xo = x.get(i).getAsJsonObject(), yo = y.get(i).getAsJsonObject();
            String xid = xo.has("id") ? xo.get("id").getAsString() : null, yid = yo.has("id") ? yo.get("id").getAsString() : null;
            if (xid == null || !xid.equals(yid)) return false;
        }
        for (int i = 0; i < x.size(); i++) {
            JsonObject yo = y.get(i).getAsJsonObject();
            if (!element(x.get(i).getAsJsonObject(), yo, yo.get("id").getAsString(), ops, false)) return false;
        }
        return true;
    }

    private static boolean sameTabs(JsonElement a, JsonElement b) {
        if (a == null || b == null) return a == b;
        JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            JsonObject xo = x.get(i).getAsJsonObject().deepCopy(), yo = y.get(i).getAsJsonObject().deepCopy();
            xo.remove("children");
            yo.remove("children");
            if (!xo.equals(yo)) return false;
        }
        return true;
    }

    private static void rows(JsonObject a, JsonObject b, String field, String id, JsonArray ops) {
        Map<String, JsonObject> before = byKey(a.get(field)), after = byKey(b.get(field));
        JsonArray remove = new JsonArray(), upsert = new JsonArray();
        for (String k : before.keySet()) if (!after.containsKey(k)) remove.add(k);
        for (Map.Entry<String, JsonObject> e : after.entrySet())
            if (!e.getValue().equals(before.get(e.getKey()))) upsert.add(e.getValue().deepCopy());
        // Reihenfolge nach dem Anwenden: alte Schlüssel ohne entfernte, dann neue angehängt
        List<String> expected = new ArrayList<>();
        for (String k : before.keySet()) if (after.containsKey(k)) expected.add(k);
        for (String k : after.keySet()) if (!before.containsKey(k)) expected.add(k);
        boolean reorder = !expected.equals(new ArrayList<>(after.keySet()));
        if (remove.isEmpty() && upsert.isEmpty() && !reorder) return;
        JsonObject op = new JsonObject();
        op.addProperty("op", "rows");
        op.addProperty("id", id);
        if (!remove.isEmpty()) op.add("remove", remove);
        if (!upsert.isEmpty()) op.add("upsert", upsert);
        if (reorder) {
            JsonArray order = new JsonArray();
            after.keySet().forEach(order::add);
            op.add("order", order);
        }
        ops.add(op);
    }

    private static Map<String, JsonObject> byKey(JsonElement rows) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (rows != null && rows.isJsonArray())
            for (JsonElement r : rows.getAsJsonArray()) out.put(r.getAsJsonObject().get("key").getAsString(), r.getAsJsonObject());
        return out;
    }

    private static boolean replace(String id, JsonObject b, JsonArray ops, boolean root) {
        if (root) return false;
        JsonObject op = new JsonObject();
        op.addProperty("op", "replace");
        op.addProperty("id", id);
        op.add("element", b.deepCopy());
        ops.add(op);
        return true;
    }
}
