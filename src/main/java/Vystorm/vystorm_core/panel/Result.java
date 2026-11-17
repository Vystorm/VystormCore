package Vystorm.vystorm_core.panel;

import java.util.Map;
import java.util.Objects;

/**
 * Antwort eines Handlers: {@link #ok()}, {@link #ok(String)}, {@link #error(String)}, {@link #fieldErrors(Map)};
 * {@link #success()} sagt, ob es geklappt hat (ein Accessor {@code ok()} wäre mit der Fabrik {@code ok()} gleichnamig).
 * Texte dürfen {@code Lang.ref} sein (Core löst sie in der Spielersprache auf). @since 0.25.0
 */
public record Result(boolean success, String message, Map<String, String> fields) {
    public Result {
        message = message == null ? "" : message;
        fields = Map.copyOf(Objects.requireNonNullElse(fields, Map.of()));
    }

    public static Result ok() { return new Result(true, "", Map.of()); }

    public static Result ok(String message) { return new Result(true, message, Map.of()); }

    public static Result error(String message) { return new Result(false, message, Map.of()); }

    /** Fehler an einzelnen Formularfeldern (Element-ID → Text). */
    public static Result fieldErrors(Map<String, String> fields) { return new Result(false, "", fields); }

    private static final Result PENDING = new Result(true, "", Map.of());

    /**
     * Antwort kommt später (z. B. nach einer asynchronen Datenbankabfrage): Core schickt jetzt nichts, der Handler ruft
     * {@link PanelAction#complete(Result)} – von jedem Thread – sobald das Ergebnis da ist. Ohne {@code complete} binnen
     * {@value #PENDING_TIMEOUT_SECONDS} s antwortet Core mit einem Fehler („keine Antwort“). Im Paper-Dialog-Fallback gilt
     * ein ausstehendes Ergebnis als Erfolg ohne Meldung (Dialoge haben keine Live-Aktualisierung).
     *
     * @since 0.26.4
     */
    public static Result pending() { return PENDING; }

    /** Ob dies {@link #pending()} ist. @since 0.26.4 */
    public boolean isPending() { return this == PENDING; }

    /** Zeit für {@link PanelAction#complete(Result)} nach {@link #pending()}. @since 0.26.4 */
    public static final int PENDING_TIMEOUT_SECONDS = 30;
}
