package Vystorm.vystorm_core.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an admin action will do, shown before it runs and written to the audit log afterwards.
 *
 * @param id               action id, lower case with dots or dashes ({@code gamerule.set}, {@code time.set})
 * @param text             one human line in the viewer's language ("Spawn phantoms: on -> off in world")
 * @param apiCall          the API call in plain words ({@code world.setGameRule(spawn_phantoms, false)})
 * @param command          the equivalent vanilla command with a leading slash, or {@code null} when none exists
 * @param commandNote      why there is no exact command ("vanilla /ban has no duration"), or empty
 * @param world            world name the action changes, or {@code null}
 * @param target           player or other target name, or {@code null}
 * @param before           value before, as text, or empty
 * @param after            value after, as text, or empty
 * @param confirm          whether the runner needs a confirmed call ({@code ActionsInterface#run} with
 *                         {@code confirmed = true}); dangerous actions set it
 * @param permissions      every node the actor needs (the plugin's own node AND {@code minecraft.command.<cmd>}); the
 *                         runner checks them again at run time
 * @param notifyPermission node of the staff who get a grey note like vanilla's admin command note, or empty for none
 * @since 0.73.0
 */
public record ActionPreview(String id, String text, String apiCall, String command, String commandNote, String world, String target,
                            String before, String after, boolean confirm, List<String> permissions, String notifyPermission) {
    public ActionPreview {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("invalid action id: " + id);
        text = text == null ? "" : text;
        apiCall = apiCall == null ? "" : apiCall;
        commandNote = commandNote == null ? "" : commandNote;
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        notifyPermission = notifyPermission == null ? "" : notifyPermission;
    }

    public static Builder builder(String id) { return new Builder(id); }

    /** Builder; only {@code id} is required. */
    public static final class Builder {
        private final String id;
        private String text, apiCall, command, commandNote, world, target, before, after, notify;
        private boolean confirm;
        private final List<String> permissions = new ArrayList<>();

        private Builder(String id) { this.id = Objects.requireNonNull(id); }

        public Builder text(String v) { text = v; return this; }
        public Builder apiCall(String v) { apiCall = v; return this; }
        public Builder command(String v) { command = v; return this; }
        public Builder commandNote(String v) { commandNote = v; return this; }
        public Builder world(String v) { world = v; return this; }
        public Builder target(String v) { target = v; return this; }
        public Builder change(Object before, Object after) { this.before = before == null ? "" : before.toString(); this.after = after == null ? "" : after.toString(); return this; }
        public Builder confirm(boolean v) { confirm = v; return this; }
        public Builder permission(String node) { if (node != null && !node.isBlank()) permissions.add(node); return this; }
        public Builder notify(String node) { notify = node; return this; }

        public ActionPreview build() {
            return new ActionPreview(id, text, apiCall, command, commandNote, world, target, before, after, confirm, permissions, notify);
        }
    }
}
