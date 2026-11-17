package Vystorm.vystorm_core.threat;

import java.time.Duration;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Central thread-safe threat modifier service shared by Vystorm plugins. */
public interface ThreatScoreService {

    void putModifier(
            Plugin owner,
            ThreatTarget target,
            NamespacedKey source,
            double additive,
            double multiplier,
            int priority,
            Duration ttl,
            boolean persistent);

    default void addPoints(
            Plugin owner,
            ThreatTarget target,
            NamespacedKey source,
            double amount,
            Duration ttl) {
        putModifier(owner, target, source, amount, 1.0D, 0, ttl, false);
    }

    default void setMultiplier(
            Plugin owner,
            ThreatTarget target,
            NamespacedKey source,
            double multiplier,
            Duration ttl) {
        putModifier(owner, target, source, 0.0D, multiplier, 0, ttl, false);
    }

    boolean removeModifier(Plugin owner, ThreatTarget target, NamespacedKey source);

    int removeOwner(Plugin owner);

    ThreatSnapshot snapshot(ThreatTarget target, double minimumThreat, double maximumThreat);

    default ThreatSnapshot snapshot(ThreatTarget target) {
        return snapshot(target, 0.0D, 100.0D);
    }
}
