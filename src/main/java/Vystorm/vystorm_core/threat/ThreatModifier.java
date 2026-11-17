package Vystorm.vystorm_core.threat;

import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Immutable modifier contributed by one plugin namespace. */
public record ThreatModifier(
        NamespacedKey source,
        double additive,
        double multiplier,
        int priority,
        long expiresAtEpochMilli,
        boolean persistent) {

    public ThreatModifier {
        Objects.requireNonNull(source, "source");
        if (!Double.isFinite(additive)) {
            throw new IllegalArgumentException("additive must be finite");
        }
        if (!Double.isFinite(multiplier) || multiplier < 0.0D) {
            throw new IllegalArgumentException("multiplier must be finite and >= 0");
        }
    }

    public boolean isExpired(long nowEpochMilli) {
        return expiresAtEpochMilli != Long.MAX_VALUE && nowEpochMilli >= expiresAtEpochMilli;
    }
}
