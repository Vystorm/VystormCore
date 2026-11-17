// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, UiSchema.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Liest und prüft Element-JSON (Schema v1, siehe {@code PROTOCOL.md}). Streng: falsche Typen, Werte außerhalb der
 * Grenzen, unbekannte Element-Typen und zu tiefe Verschachtelung sind Fehler. Unbekannte Felder werden ignoriert
 * (Vorwärtskompatibilität). Fehlermeldungen nennen nur Feldnamen, nie Inhalte.
 */
public final class UiSchema {
    private static final Gson GSON = new Gson();
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private UiSchema() {}

    /** Prüffehler mit Code ({@code Protocol.REJECT_*}) und kurzer Beschreibung. */
    public static final class SchemaException extends RuntimeException {
        private final int code;

        public SchemaException(int code, String detail) {
            super(detail, null, false, false);
            this.code = code;
        }

        public int code() { return code; }
    }

    // ------------------------------------------------------------------ Einstieg

    /** JSON-Text streng als Objekt lesen (Tiefe, Syntax, keine Daten nach dem Objekt). */
    public static JsonObject parseObject(String json, int maxBytes) {
        if (json == null) throw invalid("json missing");
        if (json.length() > maxBytes) throw invalid("json too large");
        if (depth(json) > UiLimits.MAX_JSON_DEPTH) throw invalid("json nested too deeply");
        try {
            JsonReader reader = Vystorm.vystorm_core.compat.JsonCompat.reader(json);
            JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing data after json");
            if (element == null || !element.isJsonObject()) throw invalid("json must be an object");
            return element.getAsJsonObject();
        } catch (IOException | RuntimeException bad) {
            if (bad instanceof SchemaException schema) throw schema;
            throw invalid("json syntax");
        }
    }

    /** Element aus einem PUT (oder einem zusammengeführten PATCH). */
    public static UiElement element(JsonObject o) {
        String id = str(o, "id", true, 64);
        if (!ElementIds.valid(id)) throw invalid("id invalid");
        String type = str(o, "type", true, 16);
        return switch (type) {
            case "panel" -> panel(id, o);
            case "bar" -> bar(id, o);
            case "slots" -> slots(id, o);
            case "marker" -> marker(id, o);
            case "tooltip" -> tooltip(id, o);
            case "list" -> list(id, o);
            default -> throw new SchemaException(Protocol.REJECT_UNKNOWN_TYPE, "unknown type");
        };
    }

    /** Toast aus einer TOAST-Nachricht. */
    public static ToastSpec toast(JsonObject o) {
        UiText title = text(o.get("title"), "title");
        if (title == null || title.isEmpty()) throw invalid("title missing");
        UiText text = o.has("text") ? text(o.get("text"), "text") : UiText.EMPTY;
        Icon icon = o.has("icon") ? icon(o.get("icon"), "icon") : null;
        int duration = integer(o, "durationMs", 5000, UiLimits.MIN_TOAST_MS, UiLimits.MAX_TOAST_MS);
        ToastSpec.Style style = ToastSpec.Style.parse(o.has("style") ? str(o, "style", true, 16) : null);
        if (style == null) throw invalid("style invalid");
        ToastSpec.Action action = null;
        if (o.has("action") && !o.get("action").isJsonNull()) {
            JsonElement a = o.get("action");
            if (!a.isJsonObject()) throw invalid("action must be an object");
            String actionId = str(a.getAsJsonObject(), "id", true, 64);
            if (!ElementIds.valid(actionId)) throw invalid("action.id invalid");
            UiText label = a.getAsJsonObject().has("label")
                    ? text(a.getAsJsonObject().get("label"), "action.label", 4, UiLimits.MAX_ACTION_LABEL_CHARS) : UiText.EMPTY;
            action = new ToastSpec.Action(actionId, label);
        }
        return new ToastSpec(title, text, icon, duration, style, action);
    }

    // ------------------------------------------------------------------ Client 0.9.0 (CAP_UI_EXTENSIONS)

    /**
     * Ob ein Element Typen/Felder von Client 0.9.0 braucht, ohne die ein älterer Client es ablehnen oder falsch zeigen
     * würde: {@code list}, Tooltip-Platzhalter und -Bedingungen außer {@code equals}, Platzhalter im {@code bar}-Label.
     * Ein Server schickt solche Elemente nur an Clients mit {@code CAP_UI_EXTENSIONS}.
     */
    public static boolean needsExtensions(UiElement e) {
        return switch (e) {
            case UiElement.HudList ignored -> true;
            case UiElement.Tooltip t -> t.needsExtensions();
            case UiElement.Bar b -> b.needsExtensions();
            default -> false;
        };
    }

    /** Ob ein Toast Client 0.9.0 braucht (Aktion). */
    public static boolean needsExtensions(ToastSpec t) { return t.action() != null; }

    /**
     * Felder von Client 0.9.0, die nur verbessern: ältere Clients ignorieren sie (unbekannte Felder), das Element bleibt
     * brauchbar. Ein Server darf sie für ältere Clients weglassen. {@code type} = Elementtyp.
     */
    public static List<String> optionalExtensionFields(String type) {
        return switch (type == null ? "" : type) {
            case "bar" -> List.of("stack", "rate");
            case "panel", "slots" -> List.of("stack");
            case "marker" -> List.of("entity", "yOffset", "ground");
            default -> List.of();
        };
    }

    /**
     * PATCH: oberste Felder von {@code patch} über eine Kopie von {@code base} legen. {@code id} muss passen,
     * {@code type} darf nicht wechseln.
     */
    public static JsonObject merge(JsonObject base, JsonObject patch) {
        JsonObject out = base.deepCopy();
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            String key = e.getKey();
            if (key.equals("id")) continue;
            if (key.equals("type") && !e.getValue().equals(base.get("type"))) throw invalid("type cannot change");
            out.add(key, e.getValue().deepCopy());
        }
        return out;
    }

    // ------------------------------------------------------------------ Elemente

    private static UiElement.Panel panel(String id, JsonObject o) {
        Common c = common(o);
        int width = integer(o, "width", 0, 0, UiLimits.MAX_PANEL_WIDTH);
        if (width != 0 && width < 16) throw invalid("width too small");
        int padding = integer(o, "padding", 4, 0, 16);
        int background = color(o, "background", 0x90000000);
        int border = color(o, "border", 0);
        int textColor = color(o, "textColor", 0xFFFFFFFF);
        UiText title = o.has("title") ? text(o.get("title"), "title") : UiText.EMPTY;
        List<UiText> lines = textList(o, "lines", UiLimits.MAX_PANEL_LINES, false);
        Icon icon = o.has("icon") ? icon(o.get("icon"), "icon") : null;
        if (title.isEmpty() && lines.isEmpty()) throw invalid("panel needs title or lines");
        return new UiElement.Panel(id, c.anchor, c.x, c.y, c.order, c.stack, c.ttl, width, padding, background, border, textColor, title, lines, icon);
    }

    private static UiElement.Bar bar(String id, JsonObject o) {
        Common c = common(o);
        int width = integer(o, "width", 182, UiLimits.MIN_BAR_WIDTH, UiLimits.MAX_BAR_WIDTH);
        int height = integer(o, "height", 5, UiLimits.MIN_BAR_HEIGHT, UiLimits.MAX_BAR_HEIGHT);
        double max = number(o, "max", 1, Double.MIN_VALUE, 1e12);
        double value = number(o, "value", 0, 0, 1e12);
        double rate = number(o, "rate", 0, -UiLimits.MAX_RATE, UiLimits.MAX_RATE);
        int color = color(o, "color", 0xFF80FF20);
        int background = color(o, "background", 0xC0000000);
        int textColor = color(o, "textColor", 0xFFFFFFFF);
        UiText label = o.has("label") ? text(o.get("label"), "label") : UiText.EMPTY;
        int segments = integer(o, "segments", 0, 0, UiLimits.MAX_BAR_SEGMENTS);
        return new UiElement.Bar(id, c.anchor, c.x, c.y, c.order, c.stack, c.ttl, width, height, value, max, rate, color, background, textColor, label, segments);
    }

    private static UiElement.Slots slots(String id, JsonObject o) {
        Common c = common(o);
        int size = integer(o, "size", 20, UiLimits.MIN_SLOT_SIZE, UiLimits.MAX_SLOT_SIZE);
        int gap = integer(o, "gap", 2, 0, UiLimits.MAX_SLOT_GAP);
        JsonArray list = array(o, "slots", true);
        if (list.isEmpty() || list.size() > UiLimits.MAX_SLOTS) throw invalid("slots count invalid");
        List<UiElement.Slot> slots = new ArrayList<>(list.size());
        for (JsonElement e : list) {
            if (!e.isJsonObject()) throw invalid("slot must be an object");
            JsonObject s = e.getAsJsonObject();
            Icon icon = s.has("icon") ? icon(s.get("icon"), "slot.icon") : null;
            String key = s.has("key") ? UiText.sanitize(str(s, "key", true, 16)) : "";
            if (key.codePointCount(0, key.length()) > UiLimits.MAX_KEY_CHARS) throw invalid("slot.key too long");
            int count = integer(s, "count", 0, 0, 999);
            long cooldown = longValue(s, "cooldownMs", 0, 0, UiLimits.MAX_COOLDOWN_MS);
            long total = longValue(s, "cooldownTotalMs", cooldown, 0, UiLimits.MAX_COOLDOWN_MS);
            if (total < cooldown) throw invalid("slot.cooldownTotalMs < cooldownMs");
            boolean active = bool(s, "active", false);
            UiText label = s.has("label") ? text(s.get("label"), "slot.label") : UiText.EMPTY;
            slots.add(new UiElement.Slot(icon, key, count, cooldown, total, active, label));
        }
        return new UiElement.Slots(id, c.anchor, c.x, c.y, c.order, c.stack, c.ttl, size, gap, slots);
    }

    private static UiElement.Marker marker(String id, JsonObject o) {
        long ttl = longValue(o, "ttl", 0, 0, UiLimits.MAX_TTL_MS);
        String world = null;
        if (o.has("world")) {
            world = str(o, "world", true, 128);
            if (!Icon.validResource(world)) throw invalid("world invalid");
        }
        double x = number(o, "x", Double.NaN, -UiLimits.MAX_COORD, UiLimits.MAX_COORD);
        double y = number(o, "y", Double.NaN, -UiLimits.MAX_COORD, UiLimits.MAX_COORD);
        double z = number(o, "z", Double.NaN, -UiLimits.MAX_COORD, UiLimits.MAX_COORD);
        UiText label = o.has("label") ? text(o.get("label"), "label") : UiText.EMPTY;
        int color = color(o, "color", 0xFFFFD24A);
        Icon icon = o.has("icon") ? icon(o.get("icon"), "icon") : null;
        double maxDistance = number(o, "maxDistance", 256, 1, UiLimits.MAX_MARKER_DISTANCE);
        boolean showDistance = bool(o, "showDistance", true);
        boolean clamp = bool(o, "clampToScreen", true);
        java.util.UUID entity = null;
        if (o.has("entity") && !o.get("entity").isJsonNull()) {
            String raw = str(o, "entity", true, 36);
            if (!UUID_TEXT.matcher(raw).matches()) throw invalid("entity invalid");
            entity = java.util.UUID.fromString(raw);
        }
        double yOffset = number(o, "yOffset", 0, -UiLimits.MAX_MARKER_Y_OFFSET, UiLimits.MAX_MARKER_Y_OFFSET);
        boolean ground = bool(o, "ground", false);
        return new UiElement.Marker(id, ttl, world, x, y, z, label, color, icon, maxDistance, showDistance, clamp, entity, yOffset, ground);
    }

    private static UiElement.HudList list(String id, JsonObject o) {
        Common c = common(o);
        int width = integer(o, "width", 0, 0, UiLimits.MAX_PANEL_WIDTH);
        if (width != 0 && width < UiLimits.MIN_LIST_WIDTH) throw invalid("width too small");
        int padding = integer(o, "padding", 4, 0, 16);
        int background = color(o, "background", 0x90000000);
        int border = color(o, "border", 0);
        int textColor = color(o, "textColor", 0xFFFFFFFF);
        int rightColor = color(o, "rightColor", 0xFFC8C8C8);
        UiText title = o.has("title") ? text(o.get("title"), "title") : UiText.EMPTY;
        JsonArray list = array(o, "rows", false);
        if (list.size() > UiLimits.MAX_LIST_ROWS) throw invalid("rows has too many entries");
        List<UiElement.ListRow> rows = new ArrayList<>(list.size());
        for (JsonElement e : list) {
            if (!e.isJsonObject()) throw invalid("row must be an object");
            JsonObject r = e.getAsJsonObject();
            Icon icon = r.has("icon") && !r.get("icon").isJsonNull() ? icon(r.get("icon"), "row.icon") : null;
            UiText label = r.has("label") ? text(r.get("label"), "row.label") : UiText.EMPTY;
            UiText right = r.has("right") ? text(r.get("right"), "row.right") : UiText.EMPTY;
            UiElement.RowBar bar = null;
            if (r.has("bar") && !r.get("bar").isJsonNull()) {
                if (!r.get("bar").isJsonObject()) throw invalid("row.bar must be an object");
                JsonObject b = r.getAsJsonObject("bar");
                double max = number(b, "max", 1, Double.MIN_VALUE, 1e12);
                bar = new UiElement.RowBar(number(b, "value", 0, 0, 1e12), max, color(b, "color", 0xFF80FF20),
                        number(b, "rate", 0, -UiLimits.MAX_RATE, UiLimits.MAX_RATE));
            }
            if (icon == null && label.isEmpty() && right.isEmpty() && bar == null) throw invalid("row is empty");
            rows.add(new UiElement.ListRow(icon, label, right, bar));
        }
        if (title.isEmpty() && rows.isEmpty()) throw invalid("list needs title or rows");
        return new UiElement.HudList(id, c.anchor, c.x, c.y, c.order, c.stack, c.ttl, width, padding, background, border, textColor,
                rightColor, title, rows);
    }

    private static UiElement.Tooltip tooltip(String id, JsonObject o) {
        long ttl = longValue(o, "ttl", 0, 0, UiLimits.MAX_TTL_MS);
        JsonElement matchEl = o.get("match");
        if (matchEl == null || !matchEl.isJsonObject()) throw invalid("match missing");
        JsonObject match = matchEl.getAsJsonObject();
        String item = null;
        if (match.has("item")) {
            item = str(match, "item", true, 196);
            if (!Icon.validResource(item)) throw invalid("match.item invalid");
        }
        List<UiElement.DataCondition> conditions = new ArrayList<>(1);
        if (match.has("customData")) {
            JsonElement cd = match.get("customData");
            if (cd.isJsonArray()) {
                JsonArray all = cd.getAsJsonArray();
                if (all.isEmpty() || all.size() > UiLimits.MAX_DATA_CONDITIONS) throw invalid("match.customData count invalid");
                for (JsonElement one : all) conditions.add(condition(one));
            } else {
                conditions.add(condition(cd));
            }
        }
        if (item == null && conditions.isEmpty()) throw invalid("match needs item or customData");
        List<UiText> lines = textList(o, "lines", UiLimits.MAX_TOOLTIP_LINES, true);
        int placeholders = 0;
        for (UiText line : lines) {
            try {
                placeholders += TooltipData.placeholders(line).size();
            } catch (IllegalArgumentException bad) {
                throw invalid("lines: " + bad.getMessage());
            }
        }
        if (placeholders > UiLimits.MAX_PLACEHOLDERS_PER_RULE) throw invalid("lines: too many placeholders");
        return new UiElement.Tooltip(id, ttl, item, conditions, lines, placeholders > 0);
    }

    /** {@code {path, equals}}, {@code {path, present: true}} oder {@code {path, equalsViewer: true}} (genau eins). */
    private static UiElement.DataCondition condition(JsonElement e) {
        if (!e.isJsonObject()) throw invalid("match.customData must be an object");
        JsonObject cd = e.getAsJsonObject();
        String path = str(cd, "path", true, UiLimits.MAX_DATA_PATH);
        if (path.split("\\.", -1).length > 4) throw invalid("match.customData.path too deep");
        if (!TooltipData.validPath(path)) throw invalid("match.customData.path invalid");
        int kinds = (cd.has("equals") ? 1 : 0) + (cd.has("present") ? 1 : 0) + (cd.has("equalsViewer") ? 1 : 0);
        if (kinds != 1) throw invalid("match.customData needs exactly one of equals/present/equalsViewer");
        if (cd.has("equals")) return new UiElement.DataCondition(path, UiElement.DataMatch.EQUALS, str(cd, "equals", true, UiLimits.MAX_DATA_VALUE));
        if (cd.has("present")) {
            if (!bool(cd, "present", false)) throw invalid("match.customData.present must be true");
            return new UiElement.DataCondition(path, UiElement.DataMatch.PRESENT, null);
        }
        if (!bool(cd, "equalsViewer", false)) throw invalid("match.customData.equalsViewer must be true");
        return new UiElement.DataCondition(path, UiElement.DataMatch.VIEWER, null);
    }

    // ------------------------------------------------------------------ Bausteine

    private record Common(Anchor anchor, int x, int y, int order, boolean stack, long ttl) {}

    private static Common common(JsonObject o) {
        Anchor anchor = Anchor.TOP_LEFT;
        if (o.has("anchor")) {
            anchor = Anchor.parse(str(o, "anchor", true, 16));
            if (anchor == null) throw invalid("anchor invalid");
        }
        int x = integer(o, "x", 0, -UiLimits.MAX_OFFSET, UiLimits.MAX_OFFSET);
        int y = integer(o, "y", 0, -UiLimits.MAX_OFFSET, UiLimits.MAX_OFFSET);
        int order = integer(o, "order", 0, -UiLimits.MAX_ORDER, UiLimits.MAX_ORDER);
        long ttl = longValue(o, "ttl", 0, 0, UiLimits.MAX_TTL_MS);
        return new Common(anchor, x, y, order, bool(o, "stack", false), ttl);
    }

    /**
     * Text: String, Objekt {@code {text,color,bold,italic,underlined,strikethrough}} oder Liste davon, mit den
     * HUD-Grenzen (≤ 16 Abschnitte, ≤ 256 Zeichen).
     */
    static UiText text(JsonElement e, String field) { return text(e, field, UiLimits.MAX_SEGMENTS, UiLimits.MAX_TEXT_CHARS); }

    /** Text mit eigenen Grenzen (Panels: 64 Abschnitte, 2048 Zeichen). Grenzen werden abgelehnt, nicht gekürzt. */
    public static UiText text(JsonElement e, String field, int maxSegments, int maxChars) {
        if (e == null || e.isJsonNull()) return UiText.EMPTY;
        List<UiText.Segment> segments = new ArrayList<>();
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() > maxSegments) throw invalid(field + " has too many segments");
            for (JsonElement part : a) segments.add(segment(part, field, maxChars));
        } else {
            segments.add(segment(e, field, maxChars));
        }
        int chars = 0;
        for (UiText.Segment s : segments) chars += s.text().length();
        if (chars > maxChars) throw invalid(field + " too long");
        return new UiText(segments);
    }

    private static UiText.Segment segment(JsonElement e, String field, int maxChars) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return new UiText.Segment(UiText.sanitize(e.getAsString()), null, false, false, false, false);
        }
        if (!e.isJsonObject()) throw invalid(field + " must be text");
        JsonObject o = e.getAsJsonObject();
        String text = UiText.sanitize(str(o, "text", true, maxChars * 4));
        Integer color = null;
        if (o.has("color")) {
            color = Colors.parse(str(o, "color", true, 16));
            if (color == null) throw invalid(field + ".color invalid");
        }
        return new UiText.Segment(text, color, bool(o, "bold", false), bool(o, "italic", false), bool(o, "underlined", false),
                bool(o, "strikethrough", false));
    }

    private static List<UiText> textList(JsonObject o, String field, int max, boolean required) {
        if (!o.has(field)) {
            if (required) throw invalid(field + " missing");
            return List.of();
        }
        JsonArray a = array(o, field, true);
        if (a.size() > max) throw invalid(field + " has too many lines");
        if (required && a.isEmpty()) throw invalid(field + " empty");
        List<UiText> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(text(e, field));
        return out;
    }

    /** {@code {"item":"ns:path","model":"ns:path"}}, {@code {"sprite":"ns:path"}} oder {@code {"image":"/pfad.png"}}. */
    public static Icon icon(JsonElement e, String field) {
        if (e == null || !e.isJsonObject()) throw invalid(field + " must be an object");
        JsonObject o = e.getAsJsonObject();
        int kinds = (o.has("item") ? 1 : 0) + (o.has("sprite") ? 1 : 0) + (o.has("image") ? 1 : 0);
        if (kinds != 1) throw invalid(field + " needs exactly one of item/sprite/image");
        if (o.has("image")) {
            String path = str(o, "image", true, 200);
            if (!Icon.validImagePath(path)) throw invalid(field + ".image invalid");
            return new Icon(Icon.Kind.IMAGE, path, null);
        }
        if (o.has("item")) {
            String item = str(o, "item", true, 196);
            if (!Icon.validResource(item)) throw invalid(field + ".item invalid");
            String model = null;
            if (o.has("model")) {
                model = str(o, "model", true, 196);
                if (!Icon.validResource(model)) throw invalid(field + ".model invalid");
            }
            return new Icon(Icon.Kind.ITEM, item, model);
        }
        String sprite = str(o, "sprite", true, 196);
        if (!Icon.validResource(sprite)) throw invalid(field + ".sprite invalid");
        return new Icon(Icon.Kind.SPRITE, sprite, null);
    }

    private static String str(JsonObject o, String field, boolean required, int maxChars) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) {
            if (required) throw invalid(field + " missing");
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(field + " must be a string");
        String v = e.getAsString();
        if (v.length() > maxChars) throw invalid(field + " too long");
        return v;
    }

    private static JsonArray array(JsonObject o, String field, boolean required) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) {
            if (required) throw invalid(field + " missing");
            return new JsonArray();
        }
        if (!e.isJsonArray()) throw invalid(field + " must be a list");
        return e.getAsJsonArray();
    }

    private static int integer(JsonObject o, String field, int def, int min, int max) {
        long v = longValue(o, field, def, min, max);
        return (int) v;
    }

    private static long longValue(JsonObject o, String field, long def, long min, long max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return def;
        double d = numberOf(e, field);
        if (d != Math.rint(d)) throw invalid(field + " must be a whole number");
        if (d < min || d > max) throw invalid(field + " out of range");
        return (long) d;
    }

    private static double number(JsonObject o, String field, double def, double min, double max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) {
            if (Double.isNaN(def)) throw invalid(field + " missing");
            return def;
        }
        double d = numberOf(e, field);
        if (d < min || d > max) throw invalid(field + " out of range");
        return d;
    }

    private static double numberOf(JsonElement e, String field) {
        if (!e.isJsonPrimitive()) throw invalid(field + " must be a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) throw invalid(field + " must be a number");
        double d;
        try {
            d = p.getAsDouble();
        } catch (NumberFormatException bad) {
            throw invalid(field + " must be a number");
        }
        if (!Double.isFinite(d)) throw invalid(field + " must be finite");
        return d;
    }

    private static boolean bool(JsonObject o, String field, boolean def) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw invalid(field + " must be true/false");
        return e.getAsBoolean();
    }

    private static int color(JsonObject o, String field, int def) {
        if (!o.has(field) || o.get(field).isJsonNull()) return def;
        Integer c = Colors.parse(str(o, field, true, 16));
        if (c == null) throw invalid(field + " invalid");
        return c;
    }

    /** Größte Verschachtelungstiefe von {@code {}}/{@code []} außerhalb von Strings. */
    public static int depth(String json) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    depth++;
                    if (depth > max) max = depth;
                    if (max > 256) return max;
                }
                case '}', ']' -> depth--;
                default -> { }
            }
        }
        return max;
    }

    static SchemaException invalid(String detail) {
        return new SchemaException(Protocol.REJECT_INVALID, detail);
    }
}
