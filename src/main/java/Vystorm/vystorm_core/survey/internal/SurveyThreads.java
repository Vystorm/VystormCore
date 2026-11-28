package Vystorm.vystorm_core.survey.internal;

import java.util.function.BooleanSupplier;

/**
 * Thread rules of the survey: the survey itself never runs on the server thread. The runtime installs the check
 * ({@code Bukkit::isPrimaryThread}); plain tests install their own.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyThreads {
    private static volatile BooleanSupplier serverThread = () -> false;

    private SurveyThreads() {}

    /** Installs the "is this the server thread" check. */
    public static void install(BooleanSupplier isServerThread) { serverThread = isServerThread == null ? () -> false : isServerThread; }

    public static boolean onServerThread() {
        try { return serverThread.getAsBoolean(); }
        catch (RuntimeException | LinkageError unknown) { return false; }
    }

    /** Throws if called on the server thread. */
    public static void assertWorker(String what) {
        if (onServerThread()) throw new IllegalStateException(what + " must not run on the server thread");
    }

    /** Throws unless called on the server thread. */
    public static void assertServer(String what) {
        if (!onServerThread()) throw new IllegalStateException(what + " must run on the server thread");
    }
}
