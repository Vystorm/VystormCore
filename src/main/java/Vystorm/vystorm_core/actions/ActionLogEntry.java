package Vystorm.vystorm_core.actions;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of an audit log: who did what, when, to what, and how it ended.
 *
 * @param time      when it ran
 * @param actorId   the player's id, or {@code null} for the console
 * @param actorName the player's name or {@code CONSOLE}
 * @param plugin    plugin that ran the action
 * @param action    action id
 * @param world     world name or empty
 * @param target    target name or empty
 * @param before    value before or empty
 * @param after     value after or empty
 * @param command   equivalent vanilla command or empty
 * @param result    {@link ActionResult#code()}
 * @param message   result message or empty
 * @since 0.73.0
 */
public record ActionLogEntry(Instant time, UUID actorId, String actorName, String plugin, String action, String world, String target,
                             String before, String after, String command, String result, String message) {
    public ActionLogEntry {
        actorName = actorName == null ? "" : actorName;
        plugin = plugin == null ? "" : plugin;
        action = action == null ? "" : action;
        world = world == null ? "" : world;
        target = target == null ? "" : target;
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        command = command == null ? "" : command;
        result = result == null ? "" : result;
        message = message == null ? "" : message;
    }
}
