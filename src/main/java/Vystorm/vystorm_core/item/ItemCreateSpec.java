package Vystorm.vystorm_core.item;

import java.util.Map;
import java.util.UUID;

/**
 * Auftrag zum Erzeugen einer Iteminstanz. {@code level <= 0}/{@code rarity == null} = Template entscheidet.
 * {@code sourceType} entspricht der Provenance (CRAFTING, LOOT_MOB, LOOT_BOSS, DUNGEON, WORLD_EVENT, QUEST, NPC_VENDOR,
 * MARKET, ADMIN, MIGRATION, SYSTEM_REWARD).
 */
public record ItemCreateSpec(String templateId, int amount, int level, String rarity, String sourceType, String sourceId, UUID actor,
                             long seed, Map<String, String> attributes) {
    public ItemCreateSpec {
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId missing");
        amount = Math.max(1, amount);
        sourceType = sourceType == null ? "SYSTEM_REWARD" : sourceType;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ItemCreateSpec of(String templateId, int amount, String sourceType, String sourceId) {
        return new ItemCreateSpec(templateId, amount, 0, null, sourceType, sourceId, null, 0, Map.of());
    }
}
