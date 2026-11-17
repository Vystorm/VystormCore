package Vystorm.vystorm_core.web.internal;

/**
 * Fortlaufender Antwortkörper (Server-Sent Events). Der Transport schreibt zuerst Status und Header der
 * {@link HttpResponseData}, dann in einer Schleife die Stücke aus {@link #next}, bis der Stream endet oder der Client
 * trennt. Core-intern.
 *
 * @since 0.18.0
 */
public interface ResponseStream {
    /** Rückgabewert von {@link #next}, wenn der Stream beendet ist. */
    String END = "\u0000end";

    /**
     * Wartet höchstens {@code timeoutMs} auf das nächste Textstück.
     *
     * @return das Stück, {@code null} bei Zeitablauf (der Transport schickt dann einen Heartbeat), oder {@link #END}
     */
    String next(long timeoutMs) throws InterruptedException;

    /** Vom Transport aufgerufen, wenn der Client getrennt hat (idempotent). */
    void close();

    /** Registriert eine Aktion, die beim Beenden durch Core läuft (z. B. Socket schließen, um blockierte Writes zu lösen). */
    void onClose(Runnable action);
}
