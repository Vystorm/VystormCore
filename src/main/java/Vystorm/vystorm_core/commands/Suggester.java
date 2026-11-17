package Vystorm.vystorm_core.commands;

import java.util.Collection;

/**
 * Tab completion of one argument. Gets the context with the arguments before this one already parsed and the text
 * typed so far; Core filters the result by that prefix (case-insensitive), so returning all candidates is fine.
 * Runs on the thread Paper/Bukkit completes on (usually the server thread; async tab completion is possible).
 *
 * @since 0.45.0
 */
@FunctionalInterface
public interface Suggester {
    Collection<String> suggest(CommandContext context, String prefix);
}
