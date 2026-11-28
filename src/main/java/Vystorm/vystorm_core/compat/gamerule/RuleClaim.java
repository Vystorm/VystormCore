package Vystorm.vystorm_core.compat.gamerule;

/**
 * A plugin's statement "I set this game rule" (for example Insomnia sets {@code spawn_phantoms}). Menus show it as
 * "also set by Insomnia": a change there may be set back by that plugin.
 *
 * @param plugin plugin name
 * @param world  world name, or {@code null} for every world
 * @param ruleId stable rule id
 * @param reason short plain text in English, or a language reference of the plugin; may be empty
 * @since 0.73.0
 */
public record RuleClaim(String plugin, String world, String ruleId, String reason) {}
