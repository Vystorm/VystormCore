package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.item.RewardPouchInterface;
import Vystorm.vystorm_core.progression.RewardEligibilityInterface;

/**
 * Loot manager, reached through {@code api.loot()}: the shared loot tables directly ({@link LootTables}) and the
 * parts around them.
 *
 * @since 0.64.0
 */
public interface LootInterface extends LootTablesInterface {
    /** Rolls with luck, tiers, pity and conditions ({@link LootRoll}). */
    default LootRollInterface rolls() { return LootRollInterface.DEFAULT; }

    /** Mob drops: ownership, level, rank and extra parts of other plugins ({@link MobLoot}). */
    default MobLootInterface mobs() { return MobLootInterface.DEFAULT; }

    /** Luck sources per player ({@link LootLuck}). */
    default LootLuckInterface luck() { return LootLuckInterface.DEFAULT; }

    /** Loading tables from {@code loot-tables.yml} ({@link LootTableConfig}). */
    default LootTableConfigInterface config() { return LootTableConfigInterface.DEFAULT; }

    /** Durable reward pouches ({@link Vystorm.vystorm_core.item.RewardPouch}). */
    default RewardPouchInterface pouches() { return RewardPouchInterface.DEFAULT; }

    /** Whether an entity may give rewards ({@link Vystorm.vystorm_core.progression.RewardEligibility}). */
    default RewardEligibilityInterface eligibility() { return RewardEligibilityInterface.DEFAULT; }
}
