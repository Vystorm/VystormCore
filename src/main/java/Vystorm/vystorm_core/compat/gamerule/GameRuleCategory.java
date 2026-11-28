package Vystorm.vystorm_core.compat.gamerule;

import java.util.Locale;

/**
 * Vystorm's own grouping of game rules for menus (vanilla has its own, different grouping). Unknown future rules are
 * {@link #OTHER}. Labels: {@code GameRulesInterface#categoryLabel}. @since 0.73.0
 */
public enum GameRuleCategory {
    MOBS, SPAWNING, PLAYER, DROPS, WORLD, CHAT, OTHER;

    /** Lower-case key, also the language key part ({@code game-rules.category.<key>}). */
    public String key() { return name().toLowerCase(Locale.ROOT); }
}
