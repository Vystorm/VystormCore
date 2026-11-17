package Vystorm.vystorm_core.item;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Loot-Kontext (Items §21.1): Quelle, Empfänger, Ort und Schwierigkeitswerte. Der Spawn Director und Mobs liefern
 * nur diese Werte; das Auswürfeln übernimmt ausschließlich Vystorm-Items.
 */
public record LootContext(String sourceType, String sourceId, UUID player, Set<UUID> party, Location location, String region,
                          String dungeon, String faction, double threat, int mobLevel, boolean elite, boolean boss, double difficulty,
                          double luck, Map<String, Double> eventModifiers) {
    public LootContext {
        party = party == null ? Set.of() : Set.copyOf(party);
        location = location == null ? null : location.clone();
        eventModifiers = eventModifiers == null ? Map.of() : Map.copyOf(eventModifiers);
    }

    public static LootContext of(String sourceType, String sourceId, UUID player, Location location) {
        return new LootContext(sourceType, sourceId, player, Set.of(), location, null, null, null, 0, 1, false, false, 1, 0, Map.of());
    }

    public LootContext withMob(int level, double threat, boolean elite, boolean boss) {
        return new LootContext(sourceType, sourceId, player, party, location, region, dungeon, faction, threat, level, elite, boss, difficulty, luck, eventModifiers);
    }
}
