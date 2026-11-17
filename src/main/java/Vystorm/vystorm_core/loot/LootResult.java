package Vystorm.vystorm_core.loot;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Result of a {@link LootRoll}: every hit with its metadata, the created items (only after
 * {@link LootRoll#generate()}) and problems (unknown tables, unresolved items) that were skipped. @since 0.44.0
 */
public record LootResult(String table, List<Hit> hits, List<ItemStack> items, List<String> problems) {
    public LootResult {
        hits = List.copyOf(hits);
        items = items == null ? List.of() : List.copyOf(items);
        problems = List.copyOf(problems);
    }

    /**
     * One drawn item entry.
     *
     * @param rarity rarity weight 0..10000 of the entry for the rolled source
     * @param probability chance of this entry on one draw of the table it came from, multiplied along
     *                    {@code table:} references (after luck, filters and conditions)
     * @param table id of the table that held the entry (a category resolves to the referenced tables)
     * @param pity true when the hit came from the guaranteed ({@link LootRoll#guarantee}) draw
     */
    public record Hit(String item, int amount, int rarity, LootTier tier, double probability, String table,
                      LootTable.Entry entry, int enchantLevel, long enchantSeed, boolean pity) {}

    public boolean isEmpty() { return hits.isEmpty(); }

    /** Rarest hit, e.g. for a "Legendary drop!" message. */
    public Optional<Hit> best() { return hits.stream().max(Comparator.comparingInt(Hit::rarity)); }

    /** Highest tier hit, or empty. */
    public Optional<LootTier> bestTier() { return best().map(Hit::tier); }
}
