package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core.panel.internal.PanelWriter;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Hilfen für Unit-Tests von Plugins: ein {@link Panel} bzw. {@link PanelSpec} zu genau dem Dokument-JSON rendern, das
 * Core an den Client schicken würde (Schema v1, inklusive Pfad-IDs, Tabellenzell-Präfixen und Namensraum bei
 * Navigation), und es mit derselben Prüfung wie der Client validieren – ohne laufenden Server.
 *
 * <pre>{@code
 * JsonObject doc = PanelTesting.render(myPlugin.minePanel(stats));
 * JsonObject buy = PanelTesting.find(doc, "buy");
 * assertEquals("Kaufen", PanelTesting.plain(buy.get("label")));
 * assertTrue(buy.get("disabled").getAsBoolean());
 * }</pre>
 *
 * Texte: Strings und Adventure-Komponenten werden wie im Spiel umgewandelt; {@code Lang.ref} eines nicht geladenen
 * Sprachpakets bleibt als Schlüssel stehen. Item-Symbole über String-IDs ({@code Ui.item("minecraft:stone", null)})
 * brauchen keine Registries. Handler werden nicht ausgeführt. Nicht für Produktionscode gedacht (kein Server-Thread
 * nötig, kein Zustand).
 *
 * @since 0.26.4
 */
public final class PanelTesting {
    /** Panel-ID für {@link #render(Panel)}. */
    public static final String TEST_PANEL_ID = "test:panel";

    private PanelTesting() {}

    /** Wie {@link #render(String, Panel, Player)} mit {@link #TEST_PANEL_ID} und ohne Betrachter. */
    public static JsonObject render(Panel panel) { return render(TEST_PANEL_ID, panel, null); }

    /**
     * Rendert und prüft ein Panel.
     *
     * @param panelId {@code <namespace>:<name>} (Namensraum für relative {@code navigate}-Ziele)
     * @param viewer  Betrachter für Sprachauflösung, darf null oder ein Mock sein
     * @return das Dokument {@code {"schema":1,"panel":…,"root":{…}}} (eigene Kopie)
     * @throws IllegalArgumentException wenn der Client das Dokument ablehnen würde (Meldung nennt das Feld) oder IDs
     *                                  ungültig/doppelt sind
     */
    public static JsonObject render(String panelId, Panel panel, Player viewer) {
        Objects.requireNonNull(panel, "panel");
        JsonObject doc;
        try {
            doc = PanelWriter.write(panelId, panel, viewer, null, null).document();
        } catch (IllegalStateException failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        try {
            PanelSchema.document(doc, Deadline.NONE);
        } catch (UiSchema.SchemaException invalid) {
            throw new IllegalArgumentException("client would reject the panel: " + invalid.getMessage(), invalid);
        }
        return doc.deepCopy();
    }

    /**
     * Ruft {@code spec.render()} mit einem Test-Kontext auf und rendert das Ergebnis wie {@link #render(String, Panel, Player)}.
     * Im Kontext liefern {@code text(key, …)} und {@code component(key, …)} den Schlüssel selbst; {@code session()} ist
     * ein frischer Speicher.
     */
    public static JsonObject render(PanelSpec spec, Player viewer, String arg) {
        Objects.requireNonNull(spec, "spec");
        Map<String, Object> state = new HashMap<>();
        PanelSession session = new PanelSession() {
            @Override public int id() { return 1; }
            @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) state.get(key); }
            @Override public void put(String key, Object value) { state.put(key, value); }
            @Override public void remove(String key) { state.remove(key); }
        };
        PanelContext ctx = new PanelContext() {
            @Override public Player player() { return viewer; }
            @Override public String arg() { return arg == null ? "" : arg; }
            @Override public String text(String key, Object... placeholders) { return key; }
            @Override public Component component(String key, Object... placeholders) { return Component.text(key); }
            @Override public PanelSession session() { return session; }
        };
        Panel panel = spec.render().apply(ctx);
        if (panel == null) throw new IllegalArgumentException("render returned null");
        return render(TEST_PANEL_ID, panel, viewer);
    }

    /** Element mit dieser ID irgendwo im Dokument (Kinder, Fußleiste, Reiter, Tabellenzellen), sonst null. */
    public static JsonObject find(JsonObject document, String elementId) {
        return document == null || elementId == null ? null : find((JsonElement) document, elementId);
    }

    private static JsonObject find(JsonElement e, String id) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.has("id") && o.get("id").isJsonPrimitive() && id.equals(o.get("id").getAsString())) return o;
            for (Map.Entry<String, JsonElement> child : o.entrySet()) {
                JsonObject hit = find(child.getValue(), id);
                if (hit != null) return hit;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) {
                JsonObject hit = find(child, id);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Alle Element-IDs in Dokumentreihenfolge (auch Pfad-IDs wie {@code ~0/1}). */
    public static List<String> ids(JsonObject document) {
        List<String> out = new ArrayList<>();
        collect(document, out);
        return out;
    }

    private static void collect(JsonElement e, List<String> out) {
        if (e == null) return;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.has("id") && o.get("id").isJsonPrimitive()) out.add(o.get("id").getAsString());
            for (Map.Entry<String, JsonElement> child : o.entrySet()) collect(child.getValue(), out);
        } else if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) collect(child, out);
        }
    }

    /** Text-JSON (String, {@code {text,…}} oder Liste davon) als einfacher Text; null → "". */
    public static String plain(JsonElement text) {
        if (text == null || text.isJsonNull()) return "";
        if (text.isJsonPrimitive()) return text.getAsString();
        if (text.isJsonObject()) return text.getAsJsonObject().has("text") ? text.getAsJsonObject().get("text").getAsString() : "";
        StringBuilder sb = new StringBuilder();
        JsonArray a = text.getAsJsonArray();
        for (JsonElement part : a) sb.append(plain(part));
        return sb.toString();
    }
}
