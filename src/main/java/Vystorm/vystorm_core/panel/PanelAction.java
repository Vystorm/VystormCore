package Vystorm.vystorm_core.panel;

import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * Eine geprüfte Aktion: Core hat Sitzung, Element, Art, Grenzen, Ratenlimit und das Recht des Panels schon geprüft; der
 * Handler prüft nur Fachregeln (Geld, Besitz, Zustand). Server-Thread. @since 0.25.0
 */
public interface PanelAction {
    Player player();

    /** Geprüfter Wert: Boolean, Double, String, Integer, {@link Ui.Sort} oder {@code Map<String,Object>} (Formular). */
    Object value();

    PanelSession session();

    /** Panel nach dem Handler neu rendern (Core diffed und schickt einen PATCH). */
    void refresh();

    /**
     * Nach diesem Handler alle Panels des Spielers schließen und dann {@code next} ausführen – z. B. einen Paper-Dialog,
     * eine Kiste oder ein anderes Plugin-Menü öffnen. Core schickt das Schließen sofort und ruft {@code next} zwei Ticks
     * später auf dem Server-Thread auf (nur, wenn der Spieler noch online ist), damit der Client das Panel geschlossen hat,
     * bevor der Dialog kommt; ein Dialog holt so nie ein totes Panel als „vorherigen Screen“ zurück. Im Paper-Dialog-
     * Fallback wird statt des nächsten Dialogs {@code next} aufgerufen. Höchstens einmal je Aktion (der letzte Aufruf zählt).
     * Standard (Implementierungen außerhalb von Core): {@code next} sofort aufrufen.
     *
     * @since 0.26.4
     */
    default void closeAndThen(Consumer<Player> next) { if (next != null) next.accept(player()); }

    /**
     * Ergebnis einer Aktion, deren Handler {@link Result#pending()} zurückgab, nachreichen. Jeder Thread (Core springt auf
     * den Server-Thread); nur der erste Aufruf zählt, spätere und Aufrufe ohne {@code pending} werden ignoriert. Nach
     * {@value Result#PENDING_TIMEOUT_SECONDS} s hat Core schon mit einem Fehler geantwortet; ein späterer Aufruf wird
     * ignoriert. Wurde {@link #refresh()} aufgerufen (auch nach dem Handler), rendert Core nach dem Ergebnis neu.
     *
     * @since 0.26.4
     */
    default void complete(Result result) { }
}
