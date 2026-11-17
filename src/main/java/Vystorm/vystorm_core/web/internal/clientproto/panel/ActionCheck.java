// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, ActionCheck.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prüft eine P_ACTION gegen das zuletzt **gesendete** Dokument (Spec §2.4 Punkt 2): Element existiert, ist interaktiv,
 * sichtbar und nicht {@code disabled} (inkl. Vorfahren), {@code kind} passt, Wert passt zu den gesendeten Grenzen.
 * Benutzt Core vor jedem Handler; der Client benutzt {@link #charsOk} für die Eingabefilter.
 */
public final class ActionCheck {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _.'-]*");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]*([.,][0-9]*)?");

    private ActionCheck() {}

    public record SortValue(String column, boolean descending) {}

    /** Abgelehnt: {@code status} = {@code STATUS_STALE} (Ziel weg/inaktiv) oder {@code STATUS_DENIED} (Art/Wert falsch). */
    public static final class Rejected extends RuntimeException {
        private final int status;

        public Rejected(int status, String detail) {
            super(detail, null, false, false);
            this.status = status;
        }

        public int status() { return status; }

        public String detail() { return getMessage(); }
    }

    public static boolean charsOk(PanelNode.Input.Chars chars, String value) {
        return switch (chars) {
            case ANY -> true;
            case NAME -> NAME.matcher(value).matches();
            case INTEGER -> INTEGER.matcher(value).matches();
            case DECIMAL -> DECIMAL.matcher(value).matches();
        };
    }

    public static PanelNode.Form formOf(PanelDocument doc, PanelNode node) {
        for (PanelNode p = doc.parent(node); p != null; p = doc.parent(p)) if (p instanceof PanelNode.Form f) return f;
        return null;
    }

    public static Object check(PanelDocument doc, String elementId, int kind, String valueJson) {
        PanelNode node = doc.find(elementId);
        if (node == null || PanelIds.auto(elementId)) throw stale("unknown element");
        if (!doc.effectivelyEnabled(node)) throw stale("element hidden or disabled");
        JsonElement value;
        try {
            value = PanelJson.parseValue(valueJson, MAX_ACTION_VALUE_BYTES);
        } catch (UiSchema.SchemaException bad) {
            throw denied("value unreadable");
        }
        if (kind == KIND_CHANGE && formOf(doc, node) != null) throw denied("form fields are sent with SUBMIT");
        return switch (node) {
            case PanelNode.Button b when kind == KIND_CLICK && b.target() instanceof PanelNode.Button.Act -> Boolean.TRUE;
            case PanelNode.ItemNode i when kind == KIND_CLICK && i.action() -> Boolean.TRUE;
            case PanelNode.Form f when kind == KIND_SUBMIT -> submit(doc, f, value);
            case PanelNode.Toggle t when kind == KIND_CHANGE -> field(node, value);
            case PanelNode.Slider s when kind == KIND_CHANGE -> field(node, value);
            case PanelNode.NumberField n when kind == KIND_CHANGE -> field(node, value);
            case PanelNode.Select s when kind == KIND_CHANGE -> field(node, value);
            case PanelNode.Input i when kind == KIND_CHANGE -> field(node, value);
            case PanelNode.Input i when kind == KIND_SEARCH && i.search() -> field(node, value);
            case PanelNode.Table t when kind == KIND_SORT && t.paging() != null -> sort(t, value);
            case PanelNode.Table t when kind == KIND_PAGE && t.paging() != null -> page(t, value);
            case PanelNode.Table t when kind == KIND_SELECT && t.select() == PanelNode.SelectMode.SINGLE ->
                    key(value, t.rows().stream().map(PanelNode.Table.Row::key).toList());
            case PanelNode.ListView l when kind == KIND_SELECT && l.select() == PanelNode.SelectMode.SINGLE ->
                    key(value, l.items().stream().map(PanelNode.ListView.Entry::key).toList());
            case PanelNode.Tabs t when kind == KIND_TAB -> tab(t, value);
            default -> throw denied("kind does not fit element");
        };
    }

    /** Wert eines Eingabeelements (CHANGE, SEARCH und je Feld in SUBMIT). */
    static Object field(PanelNode node, JsonElement v) {
        return switch (node) {
            case PanelNode.Toggle t -> {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw denied("boolean expected");
                yield v.getAsBoolean();
            }
            case PanelNode.Slider s -> onGrid(number(v), s.min(), s.max(), s.step(), -1);
            case PanelNode.NumberField n -> onGrid(number(v), n.min(), n.max(), n.step(), n.decimals());
            case PanelNode.Select s -> {
                String id = string(v);
                if (s.options().stream().noneMatch(o -> o.id().equals(id))) throw denied("no such option");
                yield id;
            }
            case PanelNode.Input i -> {
                String text = string(v);
                if (!UiText.sanitize(text).equals(text)) throw denied("control or format characters");
                if (text.codePointCount(0, text.length()) > i.maxLength() || !charsOk(i.chars(), text)) throw denied("text does not fit");
                yield text;
            }
            default -> throw denied("not an input");
        };
    }

    /**
     * Felder des Formulars; gesperrte/versteckte Felder werden ignoriert (nicht abgelehnt), damit ein Formular mit einem
     * gesperrten Feld absendbar bleibt. Unbekannte oder fremde Felder → abgelehnt.
     */
    static Map<String, Object> submit(PanelDocument doc, PanelNode.Form form, JsonElement v) {
        if (!v.isJsonObject()) throw denied("object expected");
        JsonObject o = v.getAsJsonObject();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            PanelNode field = doc.find(e.getKey());
            if (field == null || formOf(doc, field) != form) throw denied("unknown form field");
            if (!doc.effectivelyEnabled(field)) continue;
            out.put(e.getKey(), field(field, e.getValue()));
        }
        return out;
    }

    static SortValue sort(PanelNode.Table t, JsonElement v) {
        if (!v.isJsonObject()) throw denied("object expected");
        JsonObject o = v.getAsJsonObject();
        String column = o.has("column") ? string(o.get("column")) : "";
        String dir = o.has("dir") ? string(o.get("dir")) : "asc";
        if (t.columns().stream().noneMatch(c -> c.id().equals(column) && c.sortable()) || !(dir.equals("asc") || dir.equals("desc")))
            throw denied("sort invalid");
        return new SortValue(column, dir.equals("desc"));
    }

    static Integer page(PanelNode.Table t, JsonElement v) {
        double d = number(v);
        if (d != Math.rint(d) || d < 0 || d >= t.paging().pages()) throw denied("page out of range");
        return (int) d;
    }

    static String key(JsonElement v, java.util.List<String> keys) {
        String k = string(v);
        if (!keys.contains(k)) throw denied("no such row");
        return k;
    }

    static String tab(PanelNode.Tabs t, JsonElement v) {
        String id = string(v);
        if (t.tabs().stream().noneMatch(x -> x.id().equals(id) && x.lazy())) throw denied("tab not lazy");
        return id;
    }

    static double onGrid(double v, double min, double max, double step, int decimals) {
        if (v < min || v > max) throw denied("out of range");
        double steps = (v - min) / step;
        if (Math.abs(steps - Math.rint(steps)) > 1e-6) throw denied("not on step grid");
        if (decimals >= 0) {
            double scaled = v * Math.pow(10, decimals);
            if (Math.abs(scaled - Math.rint(scaled)) > 1e-6) throw denied("too many decimals");
        }
        return v;
    }

    static double number(JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw denied("number expected");
        double d = v.getAsDouble();
        if (!Double.isFinite(d)) throw denied("number not finite");
        return d;
    }

    static String string(JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw denied("string expected");
        return v.getAsString();
    }

    static Rejected stale(String d) { return new Rejected(STATUS_STALE, d); }

    static Rejected denied(String d) { return new Rejected(STATUS_DENIED, d); }
}
