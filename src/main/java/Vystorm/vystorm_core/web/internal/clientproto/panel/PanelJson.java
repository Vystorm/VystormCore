// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelJson.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ui.Icon;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
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
import java.util.Locale;

/** Strenge Feld-Leser für Panel-JSON. Fehlermeldungen nennen nur Feldnamen, nie Inhalte. */
public final class PanelJson {
    private static final Gson GSON = new Gson();

    private PanelJson() {}

    public static UiSchema.SchemaException invalid(String detail) { return new UiSchema.SchemaException(ERR_INVALID, detail); }

    public static UiSchema.SchemaException limit(String detail) { return new UiSchema.SchemaException(ERR_LIMIT, detail); }

    /** Streng als Objekt lesen: kein Lenient, Tiefe ≤ {@link PanelLimits#MAX_JSON_DEPTH}, keine Daten danach. */
    public static JsonObject parse(String json, int maxChars) {
        if (json == null) throw invalid("json missing");
        if (json.length() > maxChars) throw limit("json too large");
        if (UiSchema.depth(json) > MAX_JSON_DEPTH) throw limit("json nested too deeply");
        try {
            JsonReader reader = Vystorm.vystorm_core.compat.JsonCompat.reader(json);
            JsonElement e = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing data after json");
            if (e == null || !e.isJsonObject()) throw invalid("json must be an object");
            return e.getAsJsonObject();
        } catch (IOException | RuntimeException bad) {
            if (bad instanceof UiSchema.SchemaException s) throw s;
            throw invalid("json syntax");
        }
    }

    public static String str(JsonObject o, String f, boolean required, int maxChars) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) {
            if (required) throw invalid(f + " missing");
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(f + " must be a string");
        String v = e.getAsString();
        if (v.length() > maxChars) throw invalid(f + " too long");
        return v;
    }

    public static double number(JsonObject o, String f, double def, double min, double max) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) {
            if (Double.isNaN(def)) throw invalid(f + " missing");
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw invalid(f + " must be a number");
        double d;
        try { d = e.getAsDouble(); } catch (NumberFormatException bad) { throw invalid(f + " must be a number"); }
        if (!Double.isFinite(d) || d < min || d > max) throw invalid(f + " out of range");
        return d;
    }

    public static long longValue(JsonObject o, String f, long def, long min, long max) {
        double d = number(o, f, def, min, max);
        if (d != Math.rint(d)) throw invalid(f + " must be a whole number");
        return (long) d;
    }

    public static int integer(JsonObject o, String f, int def, int min, int max) { return (int) longValue(o, f, def, min, max); }

    public static boolean bool(JsonObject o, String f, boolean def) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw invalid(f + " must be true/false");
        return e.getAsBoolean();
    }

    public static JsonArray array(JsonObject o, String f, boolean required, int max) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) {
            if (required) throw invalid(f + " missing");
            return new JsonArray();
        }
        if (!e.isJsonArray()) throw invalid(f + " must be a list");
        if (e.getAsJsonArray().size() > max) throw invalid(f + " has too many entries");
        return e.getAsJsonArray();
    }

    public static JsonObject object(JsonElement e, String f) {
        if (e == null || !e.isJsonObject()) throw invalid(f + " must be an object");
        return e.getAsJsonObject();
    }

    public static UiText text(JsonElement e, String f) { return UiSchema.text(e, f, MAX_TEXT_SEGMENTS, MAX_TEXT_CHARS); }

    public static UiText text(JsonObject o, String f) { return o.has(f) ? text(o.get(f), f) : UiText.EMPTY; }

    public static List<UiText> texts(JsonObject o, String f, int max) {
        JsonArray a = array(o, f, false, max);
        List<UiText> out = new ArrayList<>(a.size());
        for (JsonElement e : a) out.add(text(e, f));
        return out;
    }

    /** {@code tooltip}: ein Text oder eine Liste von höchstens 8 Texten. */
    public static List<UiText> tooltip(JsonObject o) {
        JsonElement e = o.get("tooltip");
        if (e == null || e.isJsonNull()) return List.of();
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() > MAX_TOOLTIP_LINES) throw invalid("tooltip has too many lines");
            List<UiText> out = new ArrayList<>(a.size());
            for (JsonElement line : a) out.add(text(line, "tooltip"));
            return out;
        }
        return List.of(text(e, "tooltip"));
    }

    public static PanelIcon icon(JsonElement e, String f) {
        JsonObject o = object(e, f);
        int count = integer(o, "count", 0, 0, 999);
        boolean glint = bool(o, "glint", false);
        if (o.has("blob")) {
            if (o.has("item") || o.has("sprite") || o.has("image")) throw invalid(f + " needs exactly one source");
            String hash = str(o, "blob", true, 64);
            if (!PanelIds.sha256Hex(hash)) throw invalid(f + ".blob invalid");
            return new PanelIcon(null, hash, count, glint);
        }
        Icon base = UiSchema.icon(o, f);
        return new PanelIcon(base, null, count, glint);
    }

    public static PanelIcon icon(JsonObject o, String f) { return o.has(f) && !o.get(f).isJsonNull() ? icon(o.get(f), f) : null; }

    public static PanelColor color(JsonObject o, String f, PanelColor def) {
        if (!o.has(f) || o.get(f).isJsonNull()) return def;
        PanelColor c = PanelColor.parse(str(o, f, true, 24));
        if (c == null) throw invalid(f + " invalid");
        return c;
    }

    /** {@code auto}, Zahl (GUI-px 0..2000), {@code "50%"} (1..100), {@code fill}. */
    public static PanelNode.Size size(JsonObject o, String f) {
        JsonElement e = o.get(f);
        if (e == null || e.isJsonNull()) return PanelNode.Size.AUTO;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
            return new PanelNode.Size(PanelNode.Size.Kind.PX, integer(o, f, 0, 0, 2000));
        String s = str(o, f, true, 8);
        if (s.equals("auto")) return PanelNode.Size.AUTO;
        if (s.equals("fill")) return new PanelNode.Size(PanelNode.Size.Kind.FILL, 0);
        if (s.matches("[0-9]{1,3}%")) {
            int p = Integer.parseInt(s.substring(0, s.length() - 1));
            if (p >= 1 && p <= 100) return new PanelNode.Size(PanelNode.Size.Kind.PERCENT, p);
        }
        throw invalid(f + " invalid");
    }

    /** Aufzählung aus Kleinbuchstaben-Namen ({@code "mm:ss"} → {@code MM_SS}). */
    public static <E extends Enum<E>> E choice(JsonObject o, String f, E def, Class<E> type) {
        String s = str(o, f, false, 24);
        if (s == null) return def;
        try {
            return Enum.valueOf(type, s.toUpperCase(Locale.ROOT).replace(':', '_').replace('-', '_'));
        } catch (IllegalArgumentException bad) {
            throw invalid(f + " invalid");
        }
    }

    static boolean isString(JsonElement e) { return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isString(); }

    /** Beliebiger JSON-Wert (auch Primitive), streng; leerer Text = JSON {@code null}. */
    public static JsonElement parseValue(String json, int maxChars) {
        if (json == null || json.isEmpty()) return com.google.gson.JsonNull.INSTANCE;
        if (json.length() > maxChars) throw limit("value too large");
        if (UiSchema.depth(json) > 8) throw limit("value nested too deeply");
        try {
            JsonReader reader = Vystorm.vystorm_core.compat.JsonCompat.reader(json);
            JsonElement e = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing data after value");
            return e;
        } catch (IOException | RuntimeException bad) {
            if (bad instanceof UiSchema.SchemaException s) throw s;
            throw invalid("value syntax");
        }
    }
}
