package Vystorm.vystorm_core.web;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Der angemeldete Spieler und seine Browser-Sitzung, wie ihn Handler ({@link WebRequest}) und Snapshot-Quellen
 * ({@link WebSnapshot}) sehen. Nicht auf dem Server-Thread; Bukkit-Zugriffe über {@link #sync(Callable)}.
 *
 * @since 0.18.0
 */
public interface WebContext {
    /** Der angemeldete Spieler. */
    UUID playerId();

    /** Spielername zum Zeitpunkt des Logins. */
    String playerName();

    /** ID des Moduls. */
    String moduleId();

    /** Client-IP (bei Proxy/Gateway die weitergereichte, vertrauenswürdige Adresse). */
    String clientIp();

    /** Stabile, nicht geheime ID der Browser-Sitzung (nicht das Cookie). */
    String sessionId();

    /** Ob der Spieler beim Eingang der Anfrage online war. */
    boolean online();

    /**
     * Führt {@code task} auf dem Server-Thread aus und wartet höchstens das konfigurierte Sync-Timeout.
     *
     * @throws WebException 503, wenn der Server-Thread nicht rechtzeitig antwortet
     */
    <T> T sync(Callable<T> task);

    /** Wie {@link #sync(Callable)} mit eigenem Timeout (höchstens das Handler-Timeout). */
    <T> T sync(Callable<T> task, Duration timeout);

    /**
     * Language code for texts of this request (web language switch, the player's language choice, {@code language.default}
     * or last client locale, browser {@code Accept-Language}; else {@code en}). Use it with your bundle:
     * {@code lang.plain(request.language(), "web.error.no-points")}.
     *
     * @since 0.23.0
     */
    default String language() {
        try { return Vystorm.vystorm_core.lang.Localization.get().core().languageFor(playerId(), java.util.List.of()); }
        catch (RuntimeException | LinkageError failure) { return "en"; }
    }
}
