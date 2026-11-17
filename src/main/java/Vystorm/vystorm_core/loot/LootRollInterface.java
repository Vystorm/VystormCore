package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.item.ItemRefs;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Manager view of {@link LootRoll}, reached through {@code api.loot().rolls()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link LootRoll}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface LootRollInterface {
    /** The delegating default. Plugins use {@code api.loot().rolls()}. */
    LootRollInterface DEFAULT = new LootRollInterface() {};

    /** Roll a registered table or category by id ({@code category:boss_chest}, {@code vystorm_core:vanilla/rare}). */
    default LootRoll table(String id) { return LootRoll.table(id); }

    /** Roll a table object that need not be registered (its references are still looked up in the registry). */
    default LootRoll of(LootTable table) { return LootRoll.of(table); }

    /**
     * The standard mob drop roll (rule: WHAT drops is the mob's own table, HOW GOOD only its level): rolls {@code id}
     * (normally {@link MobLoot.Rank#category()}) with {@code context.self()} as the mob's own table, luck from the level
     * only and the rank's pity ({@code loot.mob-pity} in Core's config, boss: rare by default). @since 0.44.0
     */
    default LootRoll forMob(String id, RollContext context, double level, MobLoot.Rank rank) { return LootRoll.forMob(id, context, level, rank); }

    /** {@link #forMob(String, RollContext, double, MobLoot.Rank)} on the rank's category with {@code self} as own table. @since 0.44.0 */
    default LootRoll forMob(String self, double level, MobLoot.Rank rank) { return LootRoll.forMob(self, level, rank); }

    /** Creates the item stacks of one hit (enchantments, potion, amount). */
    default List<ItemStack> create(LootResult.Hit hit) { return LootRoll.create(hit); }
}
