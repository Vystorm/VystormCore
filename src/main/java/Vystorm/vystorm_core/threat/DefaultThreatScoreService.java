package Vystorm.vystorm_core.threat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

final class DefaultThreatScoreService implements ThreatScoreService {
    private record OwnedModifier(String ownerNamespace, ThreatModifier modifier) {}

    private final ConcurrentMap<ThreatTarget, ConcurrentMap<NamespacedKey, OwnedModifier>> modifiers =
            new ConcurrentHashMap<>();

    @Override
    public void putModifier(
            Plugin owner,
            ThreatTarget target,
            NamespacedKey source,
            double additive,
            double multiplier,
            int priority,
            Duration ttl,
            boolean persistent) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        String namespace = requireOwnedNamespace(owner, source);

        long now = System.currentTimeMillis();
        long expiresAt = expiry(now, ttl);
        ThreatModifier modifier = new ThreatModifier(
                source,
                additive,
                multiplier,
                priority,
                expiresAt,
                persistent);
        modifiers.computeIfAbsent(target, ignored -> new ConcurrentHashMap<>())
                .put(source, new OwnedModifier(namespace, modifier));
    }

    @Override
    public boolean removeModifier(Plugin owner, ThreatTarget target, NamespacedKey source) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        String namespace = requireOwnedNamespace(owner, source);
        ConcurrentMap<NamespacedKey, OwnedModifier> bucket = modifiers.get(target);
        if (bucket == null) {
            return false;
        }
        OwnedModifier current = bucket.get(source);
        if (current == null || !current.ownerNamespace().equals(namespace)) {
            return false;
        }
        boolean removed = bucket.remove(source, current);
        if (bucket.isEmpty()) {
            modifiers.remove(target, bucket);
        }
        return removed;
    }

    @Override
    public int removeOwner(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        String namespace = namespaceOf(owner);
        int removed = 0;
        for (var targetEntry : modifiers.entrySet()) {
            ConcurrentMap<NamespacedKey, OwnedModifier> bucket = targetEntry.getValue();
            for (var modifierEntry : bucket.entrySet()) {
                OwnedModifier value = modifierEntry.getValue();
                if (value.ownerNamespace().equals(namespace)
                        && bucket.remove(modifierEntry.getKey(), value)) {
                    removed++;
                }
            }
            if (bucket.isEmpty()) {
                modifiers.remove(targetEntry.getKey(), bucket);
            }
        }
        return removed;
    }

    /**
     * Entfernt abgelaufene Modifier aller Ziele (sonst würden sie nur beim nächsten Snapshot des Ziels verschwinden).
     * Threadsicher; Core ruft das periodisch auf.
     *
     * @return Anzahl entfernter Modifier
     * @since 0.11.1
     */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (var targetEntry : modifiers.entrySet()) {
            ConcurrentMap<NamespacedKey, OwnedModifier> bucket = targetEntry.getValue();
            for (var modifierEntry : bucket.entrySet()) {
                if (modifierEntry.getValue().modifier().isExpired(now) && bucket.remove(modifierEntry.getKey(), modifierEntry.getValue())) removed++;
            }
            if (bucket.isEmpty()) modifiers.remove(targetEntry.getKey(), bucket);
        }
        return removed;
    }

    @Override
    public ThreatSnapshot snapshot(ThreatTarget target, double minimumThreat, double maximumThreat) {
        Objects.requireNonNull(target, "target");
        if (!Double.isFinite(minimumThreat) || !Double.isFinite(maximumThreat) || minimumThreat > maximumThreat) {
            throw new IllegalArgumentException("invalid threat bounds");
        }

        long now = System.currentTimeMillis();
        ConcurrentMap<NamespacedKey, OwnedModifier> bucket = modifiers.get(target);
        List<ThreatModifier> active = new ArrayList<>();
        if (bucket != null) {
            for (var entry : bucket.entrySet()) {
                OwnedModifier owned = entry.getValue();
                ThreatModifier modifier = owned.modifier();
                if (modifier.isExpired(now)) {
                    bucket.remove(entry.getKey(), owned);
                } else {
                    active.add(modifier);
                }
            }
            if (bucket.isEmpty()) {
                modifiers.remove(target, bucket);
            }
        }

        active.sort(Comparator
                .comparingInt(ThreatModifier::priority).reversed()
                .thenComparing(modifier -> modifier.source().toString()));

        double additive = 0.0D;
        double product = 1.0D;
        for (ThreatModifier modifier : active) {
            additive += modifier.additive();
            product = safeMultiply(product, modifier.multiplier());
        }
        double raw = safeMultiply(additive, product);
        double effective = Math.max(minimumThreat, Math.min(maximumThreat, raw));
        return new ThreatSnapshot(
                target,
                additive,
                product,
                effective,
                minimumThreat,
                maximumThreat,
                active,
                now);
    }

    private static long expiry(long now, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.addExact(now, ttl.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String requireOwnedNamespace(Plugin owner, NamespacedKey source) {
        String namespace = namespaceOf(owner);
        if (!source.getNamespace().equals(namespace)) {
            throw new IllegalArgumentException(
                    "Threat source namespace '" + source.getNamespace()
                            + "' does not belong to plugin namespace '" + namespace + "'");
        }
        return namespace;
    }

    private static String namespaceOf(Plugin owner) {
        return new NamespacedKey(owner, "threat-owner-probe").getNamespace().toLowerCase(Locale.ROOT);
    }

    private static double safeMultiply(double left, double right) {
        double result = left * right;
        if (Double.isNaN(result)) {
            return 0.0D;
        }
        if (result == Double.POSITIVE_INFINITY) {
            return Double.MAX_VALUE;
        }
        if (result == Double.NEGATIVE_INFINITY) {
            return -Double.MAX_VALUE;
        }
        return result;
    }
}
