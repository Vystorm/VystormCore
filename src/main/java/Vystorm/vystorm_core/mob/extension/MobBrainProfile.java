package Vystorm.vystorm_core.mob.extension;

import java.util.Map;

/**
 * AI-Archetyp: Gewichte je Utility-Kategorie plus Kampfabstand. {@code kite}: hält Abstand, {@code fleeBelow}:
 * HP-Anteil, unter dem Flucht attraktiv wird.
 */
public record MobBrainProfile(String id, Map<String, Double> weights, double preferredRange, boolean kite, double fleeBelow, double threat) {
    public MobBrainProfile {
        weights = weights == null ? Map.of() : Map.copyOf(weights);
    }

    public double weight(String category) { return weights.getOrDefault(category, 1.0); }
}
