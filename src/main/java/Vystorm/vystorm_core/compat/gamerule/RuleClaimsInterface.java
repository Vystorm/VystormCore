package Vystorm.vystorm_core.compat.gamerule;

import Vystorm.vystorm_core.compat.gamerule.internal.RuleClaimRegistry;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * Rule claims, reached through {@code api.compat().gameRules().claims()}: a plugin declares the game rules it sets, so
 * admin menus can show "also set by X" (a change there may be set back). Claims are informational; nothing is blocked.
 * Released automatically when the plugin is disabled. Any thread. @since 0.73.0
 */
public interface RuleClaimsInterface {
    RuleClaimsInterface DEFAULT = new RuleClaimsInterface() {};

    /**
     * Declares that {@code owner} sets the rule in {@code world} ({@code null} or {@code "*"} = every world).
     * {@code ruleId} may be the stable id, the 26.x id or the 1.20.6 name. A second claim for the same world and rule
     * replaces the first. At most 512 claims per plugin.
     */
    default void claim(Plugin owner, String world, String ruleId, String reason) { RuleClaimRegistry.shared().claim(owner.getName(), world, ruleId, reason); }

    /** Withdraws one claim ({@code ruleId} null = all of that world). */
    default void release(Plugin owner, String world, String ruleId) { RuleClaimRegistry.shared().release(owner.getName(), world, ruleId); }

    /** Withdraws every claim of the plugin. */
    default void release(Plugin owner) { RuleClaimRegistry.shared().release(owner.getName()); }

    /** Claims for a rule in a world, including the claims for every world. */
    default List<RuleClaim> claims(String world, String ruleId) { return RuleClaimRegistry.shared().claims(world, ruleId); }

    /** All claims. */
    default List<RuleClaim> all() { return RuleClaimRegistry.shared().all(); }
}
