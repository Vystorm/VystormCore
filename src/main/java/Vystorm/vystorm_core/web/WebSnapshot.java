package Vystorm.vystorm_core.web;

import com.google.gson.JsonElement;
import java.util.Map;

/**
 * Liefert den aktuellen Live-Zustand eines Spielers für ein Modul, wenn sich ein Browser-Tab oder das Overlay mit dem
 * Live-Kanal verbindet ({@code GET /core/live}). Schlüssel sind Ereignistypen wie bei
 * {@link WebServices#push(java.util.UUID, String, String, JsonElement)}, z. B. {@code "character" → {level, xp, points}}.
 * Danach kommen nur noch die gepushten Änderungen. Läuft nicht auf dem Server-Thread (Timeout wie Handler).
 *
 * @since 0.18.0
 */
@FunctionalInterface
public interface WebSnapshot {
    Map<String, JsonElement> snapshot(WebContext context) throws Exception;
}
