package Vystorm.vystorm_core.threat;

import java.util.List;
import java.util.Objects;

/** Immutable explanation of a calculated threat value. */
public record ThreatSnapshot(
        ThreatTarget target,
        double additiveTotal,
        double multiplierProduct,
        double effectiveThreat,
        double minimumThreat,
        double maximumThreat,
        List<ThreatModifier> modifiers,
        long calculatedAtEpochMilli) {

    public ThreatSnapshot {
        Objects.requireNonNull(target, "target");
        modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
    }
}
