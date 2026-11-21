package Vystorm.vystorm_core.screen.internal;

/**
 * Marks the time Core runs a plugin's handler for a screen or dialog input (since 0.66.1). A click on a screen, a key,
 * a scroll or a dialog button reaches the server as a packet, and Core calls the handler from it. Everything the
 * handler does is plugin code, not player input: a world event that fires while a handler runs (for example the
 * synthetic interact or break event a plugin fires to ask protection plugins whether the player may use a block or an
 * entity) was made by a plugin. {@link ScreenWorldGuard} lets such events through; it stops only the player's own
 * actions, which the server never fires while a handler of ours is still running on the same thread.
 */
public final class ScreenDispatch {
    private ScreenDispatch() {}

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** True while a screen or dialog input handler runs on this thread. */
    public static boolean active() { return DEPTH.get()[0] > 0; }

    /** Runs {@code handler} as a screen input handler (nested calls are fine; null does nothing). */
    public static void run(Runnable handler) {
        if (handler == null) return;
        int[] depth = DEPTH.get();
        depth[0]++;
        try { handler.run(); } finally { depth[0]--; }
    }
}
