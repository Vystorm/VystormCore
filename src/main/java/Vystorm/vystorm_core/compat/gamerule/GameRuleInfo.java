package Vystorm.vystorm_core.compat.gamerule;

/**
 * One game rule of one world as this server has it.
 *
 * @param id           stable Vystorm id, the same on every version ({@code spawn_phantoms}); unknown rules use the
 *                     server's id in lower snake case
 * @param vanillaId    the id the running server uses ({@code spawn_phantoms} on 26.x, {@code doInsomnia} on 1.20.6)
 * @param legacyName   the 1.20.6 name, or {@code null} for rules that are new in 26.x
 * @param kind         value type on THIS server ({@code doFireTick} is a switch on 1.20.6, its successor a number)
 * @param min          lowest allowed number (integer rules; 0 for switches)
 * @param max          highest allowed number (integer rules; 1 for switches)
 * @param value        current value in the stable meaning ({@link Boolean} or {@link Integer})
 * @param defaultValue the server's default in the stable meaning, or {@code null} when the server does not say
 * @param category     Vystorm category
 * @param inverted     {@code true} when the server's rule means the opposite of the stable id ({@code disableRaids}
 *                     on 1.20.6 for {@code raids}): {@link #value} is already flipped, the vanilla command is not
 * @param known        {@code false} for a rule Vystorm has no label for (a newer server); menus show its id
 * @since 0.73.0
 */
public record GameRuleInfo(String id, String vanillaId, String legacyName, GameRuleKind kind, int min, int max,
                           Object value, Object defaultValue, GameRuleCategory category, boolean inverted, boolean known) {

    /** Whether the current value differs from the default (false when the default is not known). */
    public boolean changed() { return defaultValue != null && !defaultValue.equals(value); }

    /** The value as the server's own rule stores it (flipped back for inverted rules). */
    public Object vanillaValue() { return inverted && value instanceof Boolean b ? !b : value; }
}
