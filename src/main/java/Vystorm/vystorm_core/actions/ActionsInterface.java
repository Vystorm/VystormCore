package Vystorm.vystorm_core.actions;

import Vystorm.vystorm_core.actions.internal.ActionRunner;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The admin action runner, reached through {@code api.admin().actions()}: menus that do what a vanilla command does
 * show an {@link ActionPreview} (human line, API call, equivalent command), offer "copy as command", ask before
 * dangerous actions, run with the permissions checked again, and write every run to an audit log.
 * <p>
 * Permission rule of the family: a node that is set (granted or negated) decides, also for an op; a node that is not
 * set is allowed for ops only. A menu lists the plugin's own node AND {@code minecraft.command.<cmd>} in the preview, so
 * it never allows more than typing the command. Server thread. Capability {@code admin-actions}.
 *
 * @since 0.73.0
 */
public interface ActionsInterface {
    ActionsInterface DEFAULT = new ActionsInterface() {};

    /**
     * Runs an action for {@code actor}: checks every permission of the preview, refuses a preview that needs a
     * confirm unless {@code confirmed}, runs {@code action} (exceptions are caught and logged), writes the audit line
     * (refusals for missing permissions too) and sends the staff note. Returns how it ended.
     */
    default ActionResult run(Plugin owner, CommandSender actor, ActionPreview preview, boolean confirmed, Supplier<ActionResult> action) {
        return ActionRunner.run(owner, actor, preview, confirmed, action);
    }

    /** The permission rule for one node (see the type description). */
    default boolean allowed(CommandSender actor, String node) { return ActionRunner.allowed(actor, node); }

    /** The first node the actor lacks, or {@code null} when all are allowed. */
    default String missing(CommandSender actor, List<String> nodes) { return ActionRunner.missing(actor, nodes); }

    /** Sends one chat line with the preview's command that copies it on click (or the reason there is none). */
    default void copyAsCommand(Player viewer, ActionPreview preview) { ActionRunner.copyAsCommand(viewer, preview); }

    /**
     * Runs a vanilla command for an admin who may run it (every {@code minecraft.command.<root>} of the line, inner
     * {@code execute ... run} commands included); op, deop, stop, reload and restart are never run. On Paper/Purpur the
     * answer is captured ({@code Server#createCommandSender}); on Spigot the command runs as the admin and the answer
     * goes to chat ({@link DispatchResult#captured()} false). For a player the answer is captured only for lines
     * without selectors and relative coordinates; other lines run as the player (vanilla then checks selectors too).
     * {@code done} runs on the server thread, at once or within 20 ticks (26.x runs such commands a tick later). The
     * dispatch itself writes no audit line: run it inside {@link #run} for that.
     */
    default void dispatchVanilla(Plugin owner, CommandSender actor, String commandLine, Consumer<DispatchResult> done) {
        ActionRunner.dispatchVanilla(owner, actor, commandLine, done);
    }

    /** Whether {@link #dispatchVanilla} captures answers on this server. */
    default boolean capturesAnswers() { return ActionRunner.captures(); }

    /** The plugin's audit log ({@code plugins/<Plugin>/log/}), created on first use. */
    default ActionLogInterface log(Plugin owner) { return ActionRunner.log(owner); }
}
