package Vystorm.vystorm_core.mob;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zusammengesetzter Threat-Wert (Archetyp, Level, Equipment, Abilities, Traits, Affixe, Rolle, Kontext).
 * Der Spawn Director entscheidet selbst, ob die Einheit in sein Budget passt.
 */
public record ThreatQuote(String definitionId, double total, Map<String, Double> breakdown) {
    public ThreatQuote {
        breakdown = breakdown == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(breakdown));
    }

    public static ThreatQuote unknown(String definitionId) { return new ThreatQuote(definitionId, 0, Map.of()); }
}
