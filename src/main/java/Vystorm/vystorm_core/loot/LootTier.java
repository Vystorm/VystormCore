package Vystorm.vystorm_core.loot;

import java.util.Locale;
import java.util.Optional;

/**
 * Rarity tiers as bands of the rarity weight (0 = trash .. 10000 = mythic+). One selection ticket per
 * {@code 10001 - weight}, so a LEGENDARY entry is at least 30x rarer than a COMMON one in the same list.
 * @since 0.44.0
 */
public enum LootTier {
    COMMON(0, 3999), UNCOMMON(4000, 6999), RARE(7000, 8999), EPIC(9000, 9799), LEGENDARY(9800, 10000);

    private final int min, max;
    LootTier(int min, int max) { this.min = min; this.max = max; }
    public int min() { return min; }
    public int max() { return max; }

    public static LootTier of(int weight) {
        for (LootTier tier : values()) if (weight <= tier.max) return tier;
        return LEGENDARY;
    }

    /** Parses {@code rare}, {@code RARE} or {@code rare+} (the plus is ignored; callers treat tiers as minimums). */
    public static Optional<LootTier> parse(String text) {
        if (text == null) return Optional.empty();
        String name = text.trim().toUpperCase(Locale.ROOT);
        if (name.endsWith("+")) name = name.substring(0, name.length() - 1);
        try { return Optional.of(valueOf(name)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
