package Vystorm.vystorm_core.compat.gamerule.internal;

import Vystorm.vystorm_core.compat.gamerule.GameRuleCatalog;
import Vystorm.vystorm_core.compat.gamerule.RuleClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rule claims of all plugins (pure; any thread). One claim per plugin, world and rule: a second claim replaces the
 * first. Claims of a disabled plugin are removed by Core's lifecycle ({@link #release(String)}). Core-internal.
 */
public final class RuleClaimRegistry {
    private static final RuleClaimRegistry SHARED = new RuleClaimRegistry();
    static final int MAX_PER_PLUGIN = 512;
    private final List<RuleClaim> claims = new ArrayList<>();

    public static RuleClaimRegistry shared() { return SHARED; }

    public synchronized void claim(String plugin, String world, String ruleId, String reason) {
        Objects.requireNonNull(plugin, "plugin");
        if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("rule id");
        String id = GameRuleCatalog.stableId(ruleId.trim());
        String w = world == null || world.isBlank() || world.equals("*") ? null : world;
        claims.removeIf(c -> c.plugin().equals(plugin) && Objects.equals(c.world(), w) && c.ruleId().equals(id));
        if (claims.stream().filter(c -> c.plugin().equals(plugin)).count() >= MAX_PER_PLUGIN) throw new IllegalStateException("too many rule claims");
        claims.add(new RuleClaim(plugin, w, id, reason == null ? "" : reason));
    }

    public synchronized void release(String plugin, String world, String ruleId) {
        String id = ruleId == null ? null : GameRuleCatalog.stableId(ruleId);
        String w = world == null || world.isBlank() || world.equals("*") ? null : world;
        claims.removeIf(c -> c.plugin().equals(plugin) && Objects.equals(c.world(), w) && (id == null || c.ruleId().equals(id)));
    }

    public synchronized void release(String plugin) { claims.removeIf(c -> c.plugin().equals(plugin)); }

    /** Claims for this rule in this world: world-specific ones and those for every world. */
    public synchronized List<RuleClaim> claims(String world, String ruleId) {
        String id = ruleId == null ? "" : GameRuleCatalog.stableId(ruleId);
        List<RuleClaim> out = new ArrayList<>();
        for (RuleClaim c : claims) if (c.ruleId().equals(id) && (c.world() == null || c.world().equals(world))) out.add(c);
        return out;
    }

    public synchronized List<RuleClaim> all() { return List.copyOf(claims); }

    public synchronized void clear() { claims.clear(); }
}
