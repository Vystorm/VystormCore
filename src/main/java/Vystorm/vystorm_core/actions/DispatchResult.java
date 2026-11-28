package Vystorm.vystorm_core.actions;

import java.util.List;

/**
 * Result of {@code ActionsInterface#dispatchVanilla}.
 *
 * @param ran      whether the command was handed to the server
 * @param captured whether {@link #lines} hold the server's answer (Paper/Purpur); {@code false} on Spigot, where the
 *                 answer goes to the admin's chat ("see chat")
 * @param lines    the server's answer as plain text lines (empty when not captured)
 * @param refusal  {@code null} when it ran, else {@code blocked} (op, deop, stop, reload, restart), {@code no-permission}
 *                 or {@code empty}
 * @since 0.73.0
 */
public record DispatchResult(boolean ran, boolean captured, List<String> lines, String refusal) {
    public DispatchResult { lines = List.copyOf(lines == null ? List.of() : lines); }
}
