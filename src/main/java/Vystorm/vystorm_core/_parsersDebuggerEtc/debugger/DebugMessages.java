package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * Routes internal messages through the replaceable debugger without affecting the operation.
 * <p>Preferred access: {@code api.debug().messages()}.
 */
public final class DebugMessages {
    private static final ThreadLocal<Boolean> active = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicLong nextWarning = new java.util.concurrent.atomic.AtomicLong();

    private DebugMessages() {}

    public static void low(String message) { emit(message, 0); }
    public static void mid(String message) { emit(message, 1); }
    public static void high(String message) { emit(message, 2); }

    /** Mit Fehler: Klasse, Meldung und die ersten Stacktrace-Zeilen werden angehängt. */
    public static void low(String message, Throwable failure) { emit(withFailure(message, failure), 0); }
    public static void mid(String message, Throwable failure) { emit(withFailure(message, failure), 1); }
    public static void high(String message, Throwable failure) { emit(withFailure(message, failure), 2); }

    static String withFailure(String message, Throwable failure) {
        if (failure == null) return message;
        StringBuilder text = new StringBuilder(message == null ? "" : message).append(" | ")
                .append(failure.getClass().getName()).append(": ").append(failure.getMessage());
        StackTraceElement[] trace = failure.getStackTrace();
        for (int i = 0; i < Math.min(8, trace.length); i++) text.append("\n    at ").append(trace[i]);
        for (Throwable cause = failure.getCause(); cause != null && cause != failure; cause = cause.getCause())
            text.append("\n  caused by ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
        return text.toString();
    }

    @SuppressWarnings("deprecation")
    private static void emit(String message, int level) {
        API api = main.getApi();
        if (api == null || Boolean.TRUE.equals(active.get())) return;
        active.set(true);
        try {
            DebugInterface debugger = api.debug();
            LocalDateTime now = LocalDateTime.now();
            switch (level) {
                case 0 -> debugger.low(message, now);
                case 1 -> debugger.mid(message, now);
                case 2 -> debugger.high(message, now);
                default -> throw new IllegalArgumentException("Unknown debug level");
            }
        } catch (RuntimeException failure) {
            // A logging failure must never make a completed payment appear to have failed.
            long now = System.nanoTime(), next = nextWarning.get();
            if (now >= next && nextWarning.compareAndSet(next, now + java.util.concurrent.TimeUnit.SECONDS.toNanos(5))) {
                try {
                    Logger.getLogger("vystorm_core.debug-fallback").warning(
                            "[DebugMessages] Debug output failed: " + failure.getClass().getSimpleName());
                } catch (RuntimeException ignored) {}
            }
        } finally {
            active.remove();
        }
    }
}
