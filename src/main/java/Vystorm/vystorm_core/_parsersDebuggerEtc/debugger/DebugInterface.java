package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import java.time.LocalDateTime;

public interface DebugInterface extends AutoCloseable {
	    /**
	     * Sendet die message alleine an den eingestellten Debugchannel.
	     * Hier wird alles standardmäßige gesendet und dient auch als logger.
	     * jeder punkt muss hinterlegt werden, damit es verständlich bleibt
	     *
	     * @param message Klasse, Zeile, Fehlermeldung
	     *                [Test 59] Spieler konnte nicht geladen werden.
	     *                Die Klasse steuert den Schalter in debug/states.yml; die Zeile dient zur Orientierung.
	     * @param now
	     * @deprecated Use {@code api.debug().low(message)}.
	     */
	    @Deprecated(since = "0.64.0")
	    public void low(String message, LocalDateTime now);
	    /** @deprecated Use {@code api.debug().mid(message)}. */
	    @Deprecated(since = "0.64.0")
	    public void mid(String message, LocalDateTime date);
	    /** @deprecated Use {@code api.debug().high(message)}. */
	    @Deprecated(since = "0.64.0")
	    public void high(String message, LocalDateTime date);
	    public void shutdown(String message, LocalDateTime date);

    /** Returns false if HIGH is disabled. True confirms this complete message was forced to local storage.
     * Timeout/interruption does not mean the pending message was cancelled. May block the caller.
     */
    default boolean highConfirmed(String message, LocalDateTime date, java.time.Duration timeout)
            throws java.io.IOException, InterruptedException, java.util.concurrent.TimeoutException {
        throw new UnsupportedOperationException("This debugger does not support confirmed persistence");
    }
    /** Waits for earlier queued records and forces the file. Overflow losses are reported separately. */
    default void flush(java.time.Duration timeout)
            throws java.io.IOException, InterruptedException, java.util.concurrent.TimeoutException {
        throw new UnsupportedOperationException("This debugger does not support flushing");
    }
    default void reloadStates(java.time.Duration timeout)
            throws java.io.IOException, InterruptedException, java.util.concurrent.TimeoutException {
        throw new UnsupportedOperationException("This debugger does not support reloading switches");
    }
    /** Finish pending output when the handler is replaced or the plugin shuts down. */
    @Override default void close() {}

    /**
     * Writes a low-level message with the current time. Same as {@link DebugMessages#low(String)}: never throws and
     * is silent while Core is not enabled.
     * @since 0.64.0
     */
    default void low(String message) { DebugMessages.low(message); }

    /** Like {@link #low(String)} at the middle level. @since 0.64.0 */
    default void mid(String message) { DebugMessages.mid(message); }

    /** Like {@link #low(String)} at the high level. @since 0.64.0 */
    default void high(String message) { DebugMessages.high(message); }

    /**
     * All of {@link DebugMessages}, including the variants that append a failure. They are not overloads of
     * {@link #low(String)} here, because {@code low("text", null)} in existing code would then be ambiguous with
     * {@link #low(String, LocalDateTime)}.
     * @since 0.64.0
     */
    default DebugMessagesInterface messages() { return DebugMessagesInterface.DEFAULT; }

    /** Contained provider failures and replaced registrations ({@code lifecycle.ProviderFailures}). @since 0.64.0 */
    default Vystorm.vystorm_core.lifecycle.ProviderFailuresInterface failures() { return Vystorm.vystorm_core.lifecycle.ProviderFailuresInterface.DEFAULT; }
}
