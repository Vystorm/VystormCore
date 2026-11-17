package Vystorm.vystorm_core.combat;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Central Vystorm combat policy seam.
 *
 * <p>Policies are evaluated from the highest numeric priority to the lowest. The first non-PASS
 * decision wins. The registry is intentionally small and server-thread oriented: feature plugins
 * own their rules while Core owns policy ordering and lifecycle.</p>
 * <p>Preferred access: {@code api.runtime().combat()}.
 */
public final class CombatRegistry {
    public enum Decision {
        PASS,
        ALLOW,
        DENY
    }

    /**
     * What the source player does to the target. {@link #SUPPORT} covers purely beneficial effects (healing or buff
     * potions, lingering clouds without harmful effects), so a policy can block harm without blocking help.
     *
     * @since 0.26.4
     */
    public enum Kind {
        HARM,
        SUPPORT
    }

    /** {@code kind} is {@link Kind#HARM} for direct damage and for the two-argument constructor. */
    public record Context(Player attacker, Player target, Kind kind) {
        public Context {
            Objects.requireNonNull(attacker, "attacker");
            Objects.requireNonNull(target, "target");
            if (kind == null) kind = Kind.HARM;
        }

        public Context(Player attacker, Player target) {
            this(attacker, target, Kind.HARM);
        }

        /** @since 0.26.4 */
        public boolean harmful() {
            return kind == Kind.HARM;
        }
    }

    public interface Policy {
        String id();
        int priority();
        Decision evaluate(Context context);
    }

    private record Registered(Plugin owner, Policy policy, long order) {}

    private static final Map<String, Registered> POLICIES = new LinkedHashMap<>();
    /** Vorsortierte, unveränderliche Momentaufnahme; {@link #evaluate} läuft ohne Lock darauf. */
    private static volatile List<Registered> sorted = List.of();
    private static long sequence;

    private CombatRegistry() {}

    public static synchronized void register(Plugin owner, Policy policy) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(policy, "policy");
        String id = normalized(policy.id());
        Registered current = POLICIES.get(id);
        if (current != null && current.owner() != owner) {
            throw new IllegalStateException("Combat policy id already registered: " + id);
        }
        POLICIES.put(id, new Registered(owner, policy, sequence++));
        resort();
        DebugMessages.mid("[CombatRegistry] Registered policy " + id + " from " + owner.getName());
    }

    public static synchronized boolean unregister(Plugin owner, String id) {
        Objects.requireNonNull(owner, "owner");
        Registered current = POLICIES.get(normalized(id));
        if (current == null || current.owner() != owner) return false;
        POLICIES.remove(normalized(id));
        resort();
        return true;
    }

    public static synchronized void unregisterAll(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        POLICIES.entrySet().removeIf(entry -> entry.getValue().owner() == owner);
        resort();
    }

    public static Decision evaluate(Player attacker, Player target) {
        return evaluate(new Context(attacker, target));
    }

    /** @since 0.26.4 */
    public static Decision evaluate(Player attacker, Player target, Kind kind) {
        return evaluate(new Context(attacker, target, kind));
    }

    /**
     * {@link Kind#SUPPORT} if no effect is harmful (Paper's {@code PotionEffectTypeCategory.HARMFUL}), else
     * {@link Kind#HARM}; an empty list counts as harm (e.g. a plain splash of water damaging an enderman is not support).
     *
     * @since 0.26.4
     */
    public static Kind kindOf(Iterable<PotionEffect> effects) {
        List<PotionEffectTypeCategory> categories = new ArrayList<>();
        if (effects != null) for (PotionEffect effect : effects) if (effect != null) categories.add(effect.getType().getCategory());
        return kindOfCategories(categories);
    }

    static Kind kindOfCategories(List<PotionEffectTypeCategory> categories) {
        if (categories.isEmpty()) return Kind.HARM;
        return categories.contains(PotionEffectTypeCategory.HARMFUL) ? Kind.HARM : Kind.SUPPORT;
    }

    /** Wertet die Policies ohne globalen Lock aus (fremde Policies können so nicht blockieren). Server-Thread. */
    public static Decision evaluate(Context context) {
        for (Registered entry : sorted) {
            if (!entry.owner().isEnabled()) continue;
            try {
                Decision decision = Objects.requireNonNullElse(entry.policy().evaluate(context), Decision.PASS);
                if (decision != Decision.PASS) return decision;
            } catch (RuntimeException failure) {
                DebugMessages.high("[CombatRegistry] Policy " + entry.policy().id()
                        + " failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        return Decision.PASS;
    }

    public static List<String> policies() {
        return sorted.stream().map(entry -> entry.policy().id()).toList();
    }

    public static synchronized void clear() {
        POLICIES.clear();
        resort();
    }

    private static void resort() {
        List<Registered> next = new ArrayList<>(POLICIES.values());
        next.sort(Comparator
                .comparingInt((Registered entry) -> entry.policy().priority()).reversed()
                .thenComparingLong(Registered::order));
        sorted = List.copyOf(next);
    }

    private static String normalized(String id) {
        String value = Objects.requireNonNull(id, "id").trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("Combat policy id must not be empty");
        return value;
    }
}
