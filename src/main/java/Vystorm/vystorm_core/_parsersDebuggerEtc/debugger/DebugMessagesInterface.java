package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * Manager view of {@link DebugMessages}, reached through {@code api.debug().messages()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link DebugMessages}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface DebugMessagesInterface {
    /** The delegating default. Plugins use {@code api.debug().messages()}. */
    DebugMessagesInterface DEFAULT = new DebugMessagesInterface() {};

    /** See {@link DebugMessages#low}. */
    default void low(String message) { DebugMessages.low(message); }

    /** See {@link DebugMessages#mid}. */
    default void mid(String message) { DebugMessages.mid(message); }

    /** See {@link DebugMessages#high}. */
    default void high(String message) { DebugMessages.high(message); }

    /** See {@link DebugMessages#low}. */
    default void low(String message, Throwable failure) { DebugMessages.low(message, failure); }

    /** See {@link DebugMessages#mid}. */
    default void mid(String message, Throwable failure) { DebugMessages.mid(message, failure); }

    /** See {@link DebugMessages#high}. */
    default void high(String message, Throwable failure) { DebugMessages.high(message, failure); }
}
