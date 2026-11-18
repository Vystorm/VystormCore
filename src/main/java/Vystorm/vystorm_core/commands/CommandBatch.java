package Vystorm.vystorm_core.commands;

/**
 * Several command registrations and removals in one go: the players get their changed command list once, after the
 * last change, instead of once per command. Used by Core where commands come and go while players are online (the
 * commands of menu files on a reload). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CommandBatch {
    private CommandBatch() {}

    /** Runs {@code changes} ({@code CoreCommands.register}, {@code unregister} ...) and updates the players once. */
    public static void run(Runnable changes) { Registry.INSTANCE.batch(changes); }
}
