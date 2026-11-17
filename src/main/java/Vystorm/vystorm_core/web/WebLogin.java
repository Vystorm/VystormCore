package Vystorm.vystorm_core.web;

import java.time.Instant;
import java.util.Objects;

/**
 * Ein ausgestellter Einmal-Login für einen Spieler. {@link #url()} ist der fertige Link
 * ({@code <baseUrl>/login#<token>}); das Token steht im Fragment und landet so nie in Server- oder Proxy-Logs.
 * Das Token ist geheim, gilt bis {@link #expiresAt()} und genau einmal. Andere Kanäle (z. B. ein Client-Mod über
 * Plugin-Messages) können {@link #baseUrl()} und {@link #token()} getrennt übertragen.
 *
 * @param token    43 Zeichen base64url (256 Bit)
 * @param baseUrl  öffentliche Basis-URL ohne Slash am Ende
 * @param moduleId Ziel nach dem Login, oder null für die Übersicht
 * @since 0.17.0
 */
public record WebLogin(String token, String baseUrl, String moduleId, Instant expiresAt) {
    public WebLogin {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Der klickbare Link {@code <baseUrl>/login#<token>}. */
    public String url() { return baseUrl + "/login#" + token; }

    /** Keine Token-Ausgabe in Logs. */
    @Override public String toString() { return "WebLogin[" + baseUrl + ", module=" + moduleId + ", expiresAt=" + expiresAt + "]"; }
}
