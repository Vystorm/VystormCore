package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiElement;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Hilfen für Unit-Tests von Plugins: native Elemente ({@link ClientElement}) mit genau der Prüfung des Clients
 * validieren, ohne Server und ohne Core-Interna ({@code UiSchema}). Leere Liste = der Client nimmt das Element an.
 *
 * <pre>{@code
 * assertEquals(List.of(), ClientUiTesting.validate(myHud.questList(player)));
 * assertTrue(ClientUiTesting.needsExtensions(ClientElement.list("q").title("Quests")));   // nur Client ≥ 0.9.0
 * }</pre>
 *
 * @since 0.26.4
 */
public final class ClientUiTesting {
    private ClientUiTesting() {}

    /** Prüft ein Element (lokale ID genügt). @return Fehler (Feldname + Grund), leer wenn gültig */
    public static List<String> validate(ClientElement.Element<?> element) {
        return validateJson(Objects.requireNonNull(element, "element").toJson());
    }

    /** Wie {@link #validate(ClientElement.Element)} für rohes JSON. */
    public static List<String> validateJson(JsonObject element) {
        Objects.requireNonNull(element, "element");
        String json = element.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_ELEMENT_JSON_BYTES) return List.of("element larger than 16 KiB");
        try {
            UiSchema.element(UiSchema.parseObject(json, Protocol.MAX_ELEMENT_JSON_BYTES));
            return List.of();
        } catch (UiSchema.SchemaException invalid) {
            return List.of(invalid.getMessage());
        }
    }

    /** Prüft einen Toast so, wie {@link ClientUi} ihn schickt (mit Aktion: mit von Core vergebener Aktions-ID). */
    public static List<String> validate(ClientElement.Toast toast) {
        JsonObject json = Objects.requireNonNull(toast, "toast").toJson();
        if (json.has("action") && json.get("action").isJsonObject()) json.getAsJsonObject("action").addProperty("id", "test:toast/1");
        String text = json.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > Protocol.MAX_TOAST_JSON_BYTES) return List.of("toast larger than 4 KiB");
        try {
            UiSchema.toast(UiSchema.parseObject(text, Protocol.MAX_TOAST_JSON_BYTES));
            return List.of();
        } catch (UiSchema.SchemaException invalid) {
            return List.of(invalid.getMessage());
        }
    }

    /**
     * Ob das (gültige) Element nur an Clients mit {@link ClientServices#CAP_UI_EXTENSIONS} geht ({@link ClientUi#put}
     * liefert bei älteren false): {@code list}, Tooltip-Platzhalter/-Bedingungen außer {@code equals}, Platzhalter im
     * Balken-Label. Rein verbessernde Felder ({@code stack}, {@code rate}, Marker-{@code entity}/{@code ground}) zählen
     * nicht – Core lässt sie bei älteren Clients weg.
     *
     * @throws IllegalArgumentException wenn das Element ungültig ist
     */
    public static boolean needsExtensions(ClientElement.Element<?> element) {
        try {
            UiElement parsed = UiSchema.element(UiSchema.parseObject(element.toJson().toString(), Protocol.MAX_ELEMENT_JSON_BYTES));
            return UiSchema.needsExtensions(parsed);
        } catch (UiSchema.SchemaException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
    }
}
