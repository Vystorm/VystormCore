package Vystorm.vystorm_core.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Immutable weighted draws with replacement. Pure calculation, safe off-thread. @since 0.34.0
 *
 * <p>Since 0.44.0 an entry may also reference another table ({@code table:<id>}, or {@code table:self} for the
 * caller's own table, see {@link LootRoll}), carry conditions, random enchantments, a potion type and a
 * {@code fallback} flag. Such tables are rolled by {@link LootRoll}; {@link #roll(Source, RandomGenerator)} keeps
 * its 0.34 behaviour and random sequence for plain item tables.
 *
 * <p>{@code each = true} (YAML {@code mode: each}, 0.44.0): instead of weighted draws every entry is checked on each
 * roll with its own {@code chance} (independent drops, like classic mob drop lists); weights then only set the tier.
 */
public record LootTable(int minRolls, int maxRolls, double chance, List<Entry> entries, boolean each) {
    public enum Source { MOB, CHEST }

    /** Prefix of an entry item that references another table. @since 0.44.0 */
    public static final String TABLE_PREFIX = "table:";
    /** {@code table:self}: the caller's own table ({@link RollContext#self()}). @since 0.44.0 */
    public static final String SELF = "table:self";

    /**
     * Optional entry extras. All fields are optional; {@link #NONE} means a plain entry. @since 0.44.0
     *
     * @param minEnchantLevel 0 = no enchanting; otherwise 1..30 like an enchanting table
     * @param potion potion type name (e.g. {@code healing}, {@code strong_swiftness}) for potion items, or blank
     * @param fallback only drawn when no non-fallback entry of the same table can be drawn
     * @param chance own chance 0..1 of this entry in an {@link LootTable#each() each} table (ignored in weighted tables)
     */
    public record Options(int minEnchantLevel, int maxEnchantLevel, boolean treasureEnchants, String potion,
                          Conditions conditions, boolean fallback, double chance) {
        public static final Options NONE = new Options(0, 0, false, "", Conditions.NONE, false, 1);
        public Options {
            potion = potion == null ? "" : potion.trim().toLowerCase(Locale.ROOT);
            conditions = conditions == null ? Conditions.NONE : conditions;
            if (minEnchantLevel < 0 || maxEnchantLevel < minEnchantLevel || maxEnchantLevel > 30
                    || (minEnchantLevel == 0) != (maxEnchantLevel == 0))
                throw new IllegalArgumentException("enchant levels must be 1..30 (min <= max)");
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("entry chance must be 0..1");
        }
        public Options(int minEnchantLevel, int maxEnchantLevel, boolean treasureEnchants, String potion, Conditions conditions, boolean fallback) {
            this(minEnchantLevel, maxEnchantLevel, treasureEnchants, potion, conditions, fallback, 1);
        }
        /** Same options with another entry chance. */
        public Options withChance(double value) {
            return new Options(minEnchantLevel, maxEnchantLevel, treasureEnchants, potion, conditions, fallback, value);
        }
        boolean plain() { return equals(NONE); }
    }

    /**
     * Context conditions of an entry. A condition is ignored when the roll has no value for it (no location,
     * unknown biome), so context-free rolls keep every entry. @since 0.44.0
     */
    public record Conditions(Integer minY, Integer maxY, Set<String> environments, Set<String> biomes,
                             Double minDifficulty, Double maxDifficulty) {
        public static final Conditions NONE = new Conditions(null, null, Set.of(), Set.of(), null, null);
        public Conditions {
            environments = lower(environments);
            biomes = lower(biomes).stream().map(b -> b.contains(":") ? b : "minecraft:" + b).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (minY != null && maxY != null && maxY < minY) throw new IllegalArgumentException("max-y < min-y");
            if (minDifficulty != null && maxDifficulty != null && maxDifficulty < minDifficulty)
                throw new IllegalArgumentException("max-difficulty < min-difficulty");
        }
        private static Set<String> lower(Set<String> values) {
            return values == null ? Set.of() : values.stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        /** True when the context allows this entry. */
        public boolean test(RollContext context) {
            if (context == null || equals(NONE)) return true;
            Integer y = context.y();
            if (y != null && ((minY != null && y < minY) || (maxY != null && y > maxY))) return false;
            String env = context.environment();
            if (!environments.isEmpty() && env != null && !environments.contains(env.toLowerCase(Locale.ROOT))) return false;
            String biome = context.biome();
            if (!biomes.isEmpty() && biome != null && !biomes.contains(biome.toLowerCase(Locale.ROOT))) return false;
            double d = context.difficulty();
            return (minDifficulty == null || d >= minDifficulty) && (maxDifficulty == null || d <= maxDifficulty);
        }
    }

    /** Rarity weights: 0 = trash/commonest, 10000 = mythic+/rarest. Empty item is an explicit no-drop entry. */
    public record Entry(String item, int minAmount, int maxAmount, int mobWeight, int chestWeight, Options options) {
        public Entry {
            item = Objects.requireNonNull(item).trim();
            options = options == null ? Options.NONE : options;
            if (minAmount < 1 || maxAmount < minAmount || maxAmount > 64) throw new IllegalArgumentException("amount must be 1..64");
            if (!weight(mobWeight) || !weight(chestWeight)) throw new IllegalArgumentException("rarity weights must be 0..10000");
            if (item.regionMatches(true, 0, TABLE_PREFIX, 0, TABLE_PREFIX.length()) && item.length() == TABLE_PREFIX.length())
                throw new IllegalArgumentException("table: needs a table id");
        }
        public Entry(String item, int minAmount, int maxAmount, int mobWeight, int chestWeight) {
            this(item, minAmount, maxAmount, mobWeight, chestWeight, Options.NONE);
        }
        private static boolean weight(int value) { return value >= 0 && value <= 10000; }
        /** Converts rarity rank into relative selection tickets; even mythic+ retains one ticket. */
        public double weight(Source source) { return 10001 - rarity(source); }
        /** Rarity rank 0..10000 for this source. @since 0.44.0 */
        public int rarity(Source source) { return source == Source.MOB ? mobWeight : chestWeight; }
        /** Tier of this entry for the source. @since 0.44.0 */
        public LootTier tier(Source source) { return LootTier.of(rarity(source)); }
        /** True when this entry references another table. @since 0.44.0 */
        public boolean tableRef() { return item.regionMatches(true, 0, TABLE_PREFIX, 0, TABLE_PREFIX.length()); }
        /** Referenced table id ({@code self} for {@link #SELF}), or blank. @since 0.44.0 */
        public String tableId() { return tableRef() ? item.substring(TABLE_PREFIX.length()).trim() : ""; }
    }
    public record Drop(String item, int amount) {}

    public LootTable {
        entries = List.copyOf(entries);
        if (minRolls < 0 || maxRolls < minRolls || maxRolls > 27) throw new IllegalArgumentException("rolls must be 0..27");
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("chance must be 0..1");
        if (entries.size() > 1024) throw new IllegalArgumentException("at most 1024 entries");
    }

    public LootTable(int minRolls, int maxRolls, double chance, List<Entry> entries) { this(minRolls, maxRolls, chance, entries, false); }

    /** True when an entry uses a 0.44 feature (table reference, conditions, enchanting, potion, fallback). @since 0.44.0 */
    public boolean advanced() {
        if (each) return true;
        for (Entry entry : entries) if (entry.tableRef() || !entry.options().plain()) return true;
        return false;
    }

    /**
     * Rolls item drops. Plain tables keep the exact 0.34 random sequence. Tables with references or extras are
     * rolled through {@link LootRoll} without context (conditions pass, {@code table:self} is skipped);
     * enchantments and potions are only applied by {@link LootRoll#generate()}.
     */
    public List<Drop> roll(Source source, RandomGenerator random) {
        Objects.requireNonNull(source); Objects.requireNonNull(random);
        if (advanced()) {
            List<Drop> result = new ArrayList<>();
            for (LootResult.Hit hit : LootRoll.of(this).source(source).random(random).roll().hits())
                result.add(new Drop(hit.item(), hit.amount()));
            return List.copyOf(result);
        }
        if (chance == 0 || (chance < 1 && random.nextDouble() >= chance)) return List.of();
        double total = entries.stream().mapToDouble(e -> e.weight(source)).sum();
        if (total <= 0) return List.of();
        int count = random.nextInt(minRolls, maxRolls + 1);
        List<Drop> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double pick = random.nextDouble(total);
            for (Entry entry : entries) {
                double weight = entry.weight(source);
                if (weight <= 0) continue;
                if (pick < weight) {
                    if (!entry.item().isEmpty()) result.add(new Drop(entry.item(), random.nextInt(entry.minAmount(), entry.maxAmount() + 1)));
                    break;
                }
                pick -= weight;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Converts relative weights of an old-style list (bigger = more likely, any positive scale) into rarity ranks
     * with the same odds, up to rounding (largest weight = rank 0). Plugins use it to keep legacy configs.
     * @since 0.44.0
     */
    public static int[] ranksFor(double... relativeWeights) {
        double max = 0;
        for (double w : relativeWeights) {
            if (!Double.isFinite(w) || w < 0) throw new IllegalArgumentException("weights must be finite and >= 0");
            max = Math.max(max, w);
        }
        int[] ranks = new int[relativeWeights.length];
        for (int i = 0; i < ranks.length; i++) {
            long tickets = max <= 0 ? 10001 : Math.max(1, Math.round(relativeWeights[i] / max * 10001));
            ranks[i] = (int) (10001 - Math.min(10001, tickets));
        }
        return ranks;
    }
}
