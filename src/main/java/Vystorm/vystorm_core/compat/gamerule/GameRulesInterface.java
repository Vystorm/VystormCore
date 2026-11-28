package Vystorm.vystorm_core.compat.gamerule;

import Vystorm.vystorm_core.compat.gamerule.internal.GameRuleService;
import java.util.List;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Game rules on every supported server, reached through {@code api.compat().gameRules()}.
 * <p>
 * {@code org.bukkit.GameRule} has three shapes (a final class on Paper 1.20.6, an interface on Spigot 26.x, an abstract
 * class over {@code org.bukkit.GameRules} on Paper/Purpur 26.x); a plugin compiled against one shape crashes on the
 * others as soon as it calls a method on a rule. This part never hands out a {@code GameRule}: rules are named by a
 * stable Vystorm id (the 26.x id, e.g. {@code spawn_phantoms}), which also accepts the server's id and the 1.20.6 name
 * ({@code doInsomnia}). Values are {@link Boolean} or {@link Integer} in the stable meaning: {@code raids = true} means
 * raids happen on every version, although 1.20.6 stores it as {@code disableRaids = false}.
 * <p>
 * World calls on the server thread. Capability {@code game-rules}; claims: {@code rule-claims}.
 *
 * @since 0.73.0
 */
public interface GameRulesInterface {
    GameRulesInterface DEFAULT = new GameRulesInterface() {};

    /** Which shape this server has: {@code final-class}, {@code interface}, {@code abstract-class} or {@code unavailable}. */
    default String shape() { return GameRuleService.access().shape(); }

    /** All rules of the world as this server has them, sorted by stable id. */
    default List<GameRuleInfo> rules(World world) { return GameRuleService.access().rules(world); }

    /** One rule by stable id, server id or 1.20.6 name; empty when this server does not have it. */
    default Optional<GameRuleInfo> rule(World world, String id) { return GameRuleService.access().rule(world, id); }

    /** The current value in the stable meaning. */
    default Optional<Object> value(World world, String id) { return rule(world, id).map(GameRuleInfo::value); }

    /**
     * Sets the rule to {@code value} (stable meaning; Boolean, Integer, or text {@code true}/{@code false}/digits) after a
     * type and range check, and reads it back. Does not check permissions: callers that act for a player use
     * {@code api.admin().actions()}.
     */
    default GameRuleChange set(World world, String id, Object value) { return GameRuleService.access().set(world, id, value); }

    /**
     * The vanilla command with the same effect, e.g. {@code /execute in minecraft:the_nether run gamerule
     * spawn_phantoms false} (1.20.6: {@code doInsomnia}; inverted rules get the inverted value). Empty for unknown rules.
     */
    default Optional<String> command(World world, String id, Object value) {
        return rule(world, id).map(info -> GameRuleService.command(GameRuleService.dimension(world), info, value));
    }

    /** Vystorm's table of the known rules (both versions), independent of the server. */
    default List<GameRuleCatalog.Entry> catalog() { return GameRuleCatalog.all(); }

    /** Vystorm's own short name of the rule in the viewer's language (the id for rules Vystorm does not know). */
    default String label(CommandSender viewer, String id) { return GameRuleService.label(viewer, id); }

    /** Vystorm's own one-line description in the viewer's language. */
    default String description(CommandSender viewer, String id) { return GameRuleService.description(viewer, id); }

    /** The category's name in the viewer's language. */
    default String categoryLabel(CommandSender viewer, GameRuleCategory category) { return GameRuleService.categoryLabel(viewer, category); }

    /** Rule claims: which plugin sets which rule. @since 0.73.0 */
    default RuleClaimsInterface claims() { return RuleClaimsInterface.DEFAULT; }
}
