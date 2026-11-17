package Vystorm.vystorm_core.web;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eine geprüfte API-Anfrage: Sitzung, Berechtigungen, CSRF, Ratenlimit und Körpergröße hat Core bereits geprüft,
 * bevor der {@link WebHandler} sie sieht. Alle Werte stammen trotzdem vom Browser und sind fachlich zu validieren.
 * Spieler, Sitzung und {@code sync} kommen aus {@link WebContext}.
 *
 * @since 0.17.0
 */
public interface WebRequest extends WebContext {
    WebMethod method();

    /** Die aufgerufene Route (Methode und Muster). */
    WebRoute route();

    /** Dekodierter Pfad relativ zu {@code /api/<modulId>}, z. B. {@code /nodes/12}. */
    String path();

    /** Werte der Platzhalter ({@code {name}}), dekodiert, höchstens 128 Zeichen, ohne Steuerzeichen und {@code /}. */
    Map<String, String> pathParams();

    /** Ein Platzhalterwert; wirft {@link WebException} 400, wenn er fehlt. */
    default String pathParam(String name) {
        String value = pathParams().get(name);
        if (value == null) throw WebException.badRequest(Vystorm.vystorm_core.web.internal.WebText.error("param-missing", "name", name));
        return value;
    }

    /** Query-Parameter (dekodiert). */
    Map<String, List<String>> query();

    /** Erster Wert eines Query-Parameters. */
    default Optional<String> query(String name) {
        List<String> values = query().get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /** Der JSON-Körper (strikt geparst); {@code JsonNull} ohne Körper. */
    JsonElement body();

    /** Der JSON-Körper als {@code type} (Gson, z. B. ein Record); wirft {@link WebException} 400 bei unpassendem JSON. */
    <T> T body(Class<T> type);
}
