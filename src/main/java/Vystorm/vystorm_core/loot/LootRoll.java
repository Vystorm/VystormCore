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
 * Controlled roll of a loot table or category. Build one per roll (the builder itself is not shared), then
 * {@link #roll()} (pure, thread-safe, no Bukkit calls: use it in async world populators) or {@link #generate()}
 * (also creates the items; vanilla items work on any thread, custom item providers belong on the server thread).
 *
 * <pre>{@code
 * LootResult loot = LootRoll.table("category:boss_chest")
 *         .context(RollContext.of(player).withSelf("vystormsky:boss/warden").withSource("VystormSky"))
 *         .guarantee(LootTier.EPIC)
 *         .generate();
 * }</pre>
 *
 * Draw order and random use equal {@link LootTable#roll} for plain tables without options, so seeded results stay
 * stable. @since 0.44.0
 * <p>Preferred access: {@code api.loot().rolls()}.
 */
public final class LootRoll {
    /** Nested {@code table:} references deeper than this are skipped (problem reported). */
    public static final int MAX_DEPTH = 8;
    /** Upper bound of hits per roll, also across nested tables. */
    public static final int MAX_HITS = 256;
    /** Each luck point raises the rarest entries' odds by up to this factor relative to trash. */
    public static final double LUCK_STEP = 0.25;

    private final String id;
    private final LootTable table;
    private LootTable.Source source = LootTable.Source.CHEST;
    private Integer rolls;
    private double luck;
    private int minWeight = 0, maxWeight = 10000;
    private LootTier guarantee;
    private final Set<String> exclude = new HashSet<>();
    private boolean unique;
    private RandomGenerator random;
    private Long seed;
    private RollContext context = RollContext.NONE;
    /** Mob roll ({@link #mobLevel}): luck comes only from this level, the context luck (player) is ignored. */
    private Double mobLevel;

    private LootRoll(String id, LootTable table) { this.id = id; this.table = table; }

    /** Roll a registered table or category by id ({@code category:boss_chest}, {@code vystorm_core:vanilla/rare}). */
    public static LootRoll table(String id) { return new LootRoll(Objects.requireNonNull(id).trim(), null); }
    /** Roll a table object that need not be registered (its references are still looked up in the registry). */
    public static LootRoll of(LootTable table) { return new LootRoll("", Objects.requireNonNull(table)); }

    public LootRoll source(LootTable.Source value) { source = Objects.requireNonNull(value); return this; }
    /** Overrides the table's own roll count for the top level (0..27). */
    public LootRoll rolls(int value) {
        if (value < 0 || value > 27) throw new IllegalArgumentException("rolls must be 0..27");
        rolls = value; return this;
    }
    /** Extra luck points on top of {@link RollContext#luck()}; negative values favour common entries. */
    public LootRoll luck(double value) { luck = Double.isFinite(value) ? value : 0; return this; }
    /** Only entries whose rarity weight lies in min..max (inclusive); empty "no drop" entries are skipped then. */
    public LootRoll weights(int min, int max) {
        if (min < 0 || max > 10000 || max < min) throw new IllegalArgumentException("weights must be 0..10000, min <= max");
        minWeight = min; maxWeight = max; return this;
    }
    /** Only entries of this tier or rarer ({@code tier=rare} means rare+). */
    public LootRoll minTier(LootTier tier) { return weights(tier.min(), maxWeight); }
    /** Only entries of exactly this tier. */
    public LootRoll tier(LootTier tier) { return weights(tier.min(), tier.max()); }
    /** Pity: if no hit reached this tier, the lowest hit is replaced by one draw of this tier or rarer (when the table has one). */
    public LootRoll guarantee(LootTier tier) { guarantee = tier; return this; }
    /** Item refs or {@code table:} refs that must not drop. */
    public LootRoll exclude(Collection<String> refs) { refs.forEach(r -> exclude.add(r.trim().toLowerCase(Locale.ROOT))); return this; }
    /** No item ref twice in one roll (across nested tables). */
    public LootRoll unique(boolean value) { unique = value; return this; }
    /** Deterministic roll (structures, previews, tests). */
    public LootRoll seed(long value) { seed = value; return this; }
    /** Own random source (overrides {@link #seed}). */
    public LootRoll random(RandomGenerator value) { random = value; return this; }
    public LootRoll context(RollContext value) { context = value == null ? RollContext.NONE : value; return this; }

    /**
     * Mob drop roll: the luck comes only from the mob level ({@link MobLoot#luck(double)}), never from a player
     * (the context luck is ignored, also when {@link #context} is set later), the source is {@link LootTable.Source#MOB}
     * and the level is the roll difficulty. {@link #luck(double)} still adds on top (admin previews). @since 0.44.0
     */
    public LootRoll mobLevel(double level) {
        double value = Double.isFinite(level) ? Math.max(0, level) : 0;
        mobLevel = value;
        source = LootTable.Source.MOB;
        return this;
    }

    /** Level of a mob roll, or empty for other rolls. @since 0.44.0 */
    public OptionalDouble mobLevel() { return mobLevel == null ? OptionalDouble.empty() : OptionalDouble.of(mobLevel); }

    /**
     * The standard mob drop roll (rule: WHAT drops is the mob's own table, HOW GOOD only its level): rolls {@code id}
     * (normally {@link MobLoot.Rank#category()}) with {@code context.self()} as the mob's own table, luck from the level
     * only and the rank's pity ({@code loot.mob-pity} in Core's config, boss: rare by default). @since 0.44.0
     */
    public static LootRoll forMob(String id, RollContext context, double level, MobLoot.Rank rank) {
        LootRoll roll = table(id).context(context).mobLevel(level);
        LootTier pity = MobLoot.pity(rank);
        if (pity != null) roll.guarantee(pity);
        return roll;
    }

    /** {@link #forMob(String, RollContext, double, MobLoot.Rank)} on the rank's category with {@code self} as own table. @since 0.44.0 */
    public static LootRoll forMob(String self, double level, MobLoot.Rank rank) {
        MobLoot.Rank r = rank == null ? MobLoot.Rank.COMMON : rank;
        return forMob(r.category(), RollContext.NONE.withSelf(self), level, r);
    }

    /** Draws entries without creating items. Pure; safe on any thread. */
    public LootResult roll() {
        State state = new State(random != null ? random : seed != null ? new SplittableRandom(seed) : ThreadLocalRandom.current());
        String rootId = id.isEmpty() ? "(table)" : id.toLowerCase(Locale.ROOT);
        LootTable root = table;
        if (root == null) {
            root = LootTables.find(id).orElse(null);
            if (root == null) { state.problems.add("Unknown loot table: " + id); return new LootResult(rootId, List.of(), List.of(), state.problems); }
        }
        state.roll(rootId, root, 0, 1.0, rolls == null ? -1 : rolls, minWeight, false, false);
        if (guarantee != null && state.hits.stream().noneMatch(h -> h.rarity() >= guarantee.min())) {
            int min = Math.max(minWeight, guarantee.min());
            for (int attempt = 0; attempt < 16 && min <= maxWeight; attempt++) {
                List<LootResult.Hit> before = new ArrayList<>(state.hits);
                state.hits.clear();
                state.roll(rootId, root, 0, 1.0, 1, min, true, true);
                List<LootResult.Hit> pity = new ArrayList<>(state.hits);
                state.hits.clear(); state.hits.addAll(before);
                if (pity.isEmpty()) continue;
                if (!state.hits.isEmpty()) state.hits.remove(state.hits.stream().min(Comparator.comparingInt(LootResult.Hit::rarity)).orElseThrow());
                state.hits.add(pity.getFirst());
                break;
            }
        }
        return new LootResult(rootId, state.hits, List.of(), state.problems);
    }

    /** Rolls and creates the items (stacked where possible). Unresolvable items are skipped and listed as problems. */
    public LootResult generate() {
        LootResult rolled = roll();
        List<ItemStack> items = new ArrayList<>();
        List<String> problems = new ArrayList<>(rolled.problems());
        for (LootResult.Hit hit : rolled.hits()) {
            try { items.addAll(create(hit)); }
            catch (RuntimeException | LinkageError failure) { problems.add("Unresolved loot item " + hit.item() + ": " + failure.getMessage()); }
        }
        return new LootResult(rolled.table(), rolled.hits(), items, problems);
    }

    /** Creates the item stacks of one hit (enchantments, potion, amount). */
    public static List<ItemStack> create(LootResult.Hit hit) {
        List<ItemStack> out = new ArrayList<>();
        ItemStack first = unit(hit);
        // Vanilla items stack; custom items are created one by one so each keeps its own random stats.
        boolean vanilla = hit.item().indexOf(':') < 0 || hit.item().toLowerCase(Locale.ROOT).startsWith("minecraft:");
        boolean stack = vanilla && hit.enchantLevel() == 0 && first.getMaxStackSize() > 1;
        if (stack) {
            int left = hit.amount();
            while (left > 0) {
                ItemStack copy = first.clone();
                int n = Math.min(left, first.getMaxStackSize());
                copy.setAmount(n); out.add(copy); left -= n;
            }
            return out;
        }
        out.add(first);
        for (int i = 1; i < hit.amount(); i++) out.add(unit(hit));
        return out;
    }

    private static ItemStack unit(LootResult.Hit hit) {
        ItemStack item = ItemRefs.resolve(hit.item(), 1).orElseThrow(() -> new IllegalStateException("not found"));
        if (item.getType().isAir() || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR)
            throw new IllegalStateException("air");
        String potion = hit.entry().options().potion();
        if (!potion.isEmpty() && item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.valueOf(potion.toUpperCase(Locale.ROOT)));
            item.setItemMeta(meta);
        }
        if (hit.enchantLevel() > 0)
            item = Vystorm.vystorm_core.compat.PlatformCompat.enchantWithLevels(item, hit.enchantLevel(), hit.entry().options().treasureEnchants(),
                    new java.util.Random(hit.enchantSeed()));
        return item;
    }

    private final class State {
        final RandomGenerator rnd;
        final List<LootResult.Hit> hits = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        final Set<String> used = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        /** Mob rolls: player luck replaced by the level luck, level as difficulty. */
        final RollContext ctx = mobLevel == null ? context
                : context.withLuck(MobLoot.luck(mobLevel)).withDifficulty(mobLevel);
        State(RandomGenerator rnd) { this.rnd = rnd; }

        void roll(String tableId, LootTable t, int depth, double parentP, int forcedRolls, int minW, boolean ignoreChance, boolean pity) {
            if (depth > MAX_DEPTH) { problems.add("Loot table nesting deeper than " + MAX_DEPTH + " at " + tableId); return; }
            if (stack.contains(tableId)) { problems.add("Loot table cycle at " + tableId); return; }
            stack.push(tableId);
            try {
                if (!ignoreChance && (t.chance() == 0 || (t.chance() < 1 && rnd.nextDouble() >= t.chance()))) return;
                Candidates c = candidates(t, minW);
                if (c.total <= 0) return;
                int count = forcedRolls >= 0 ? forcedRolls : rnd.nextInt(t.minRolls(), t.maxRolls() + 1);
                if (t.each() && !pity) {
                    // Independent entries: each one is checked with its own chance (luck raises it for rarer entries).
                    for (int i = 0; i < count && hits.size() < MAX_HITS; i++) {
                        if (unique && i > 0) c = candidates(t, minW);
                        for (int index = 0; index < c.entries.length && hits.size() < MAX_HITS; index++) {
                            LootTable.Entry entry = c.entries[index];
                            if (unique && used.contains(entry.item().toLowerCase(Locale.ROOT))) continue;
                            double p = Math.min(1, entry.options().chance() * c.weights[index] / entry.weight(source));
                            if (p <= 0 || (p < 1 && rnd.nextDouble() >= p)) continue;
                            take(tableId, entry, parentP * p, depth, minW, ignoreChance, pity);
                        }
                    }
                    return;
                }
                for (int i = 0; i < count && hits.size() < MAX_HITS; i++) {
                    if (unique && i > 0) { c = candidates(t, minW); if (c.total <= 0) return; }
                    double pick = rnd.nextDouble(c.total);
                    int index = Arrays.binarySearch(c.cumulative, pick);
                    index = index >= 0 ? index + 1 : -index - 1;
                    if (index >= c.entries.length) index = c.entries.length - 1;
                    take(tableId, c.entries[index], parentP * c.weights[index] / c.total, depth, minW, ignoreChance, pity);
                }
            } finally {
                stack.pop();
            }
        }

        /** Adds one drawn entry: an empty draw, a nested table roll or an item hit. */
        void take(String tableId, LootTable.Entry entry, double p, int depth, int minW, boolean ignoreChance, boolean pity) {
            if (entry.item().isEmpty()) return;
            int amount = rnd.nextInt(entry.minAmount(), entry.maxAmount() + 1);
            if (entry.tableRef()) {
                String ref = refId(entry);
                LootTable nested = LootTables.find(ref).orElse(null);
                if (nested == null) { problems.add("Unknown loot table: " + ref); return; }
                for (int k = 0; k < amount && hits.size() < MAX_HITS; k++)
                    roll(ref.toLowerCase(Locale.ROOT), nested, depth + 1, p, -1, minW, ignoreChance, pity);
                return;
            }
            int level = 0; long enchantSeed = 0;
            var options = entry.options();
            if (options.minEnchantLevel() > 0) {
                level = rnd.nextInt(options.minEnchantLevel(), options.maxEnchantLevel() + 1);
                enchantSeed = rnd.nextLong();
            }
            int rarity = entry.rarity(source);
            hits.add(new LootResult.Hit(entry.item(), amount, rarity, LootTier.of(rarity), p, tableId, entry, level, enchantSeed, pity));
            if (unique) used.add(entry.item().toLowerCase(Locale.ROOT));
        }

        String refId(LootTable.Entry entry) {
            String ref = entry.tableId();
            return ref.equalsIgnoreCase("self") ? ctx.self() : ref;
        }

        Candidates candidates(LootTable t, int minW) {
            boolean filtered = minW > 0 || maxWeight < 10000;
            double luckPoints = Math.max(-3, Math.min(20, luck + ctx.luck()));
            double base = 1 + LUCK_STEP * luckPoints;
            List<LootTable.Entry> normal = new ArrayList<>(), fallback = new ArrayList<>();
            for (LootTable.Entry entry : t.entries()) {
                if (!eligible(entry, minW, filtered)) continue;
                (entry.options().fallback() ? fallback : normal).add(entry);
            }
            List<LootTable.Entry> chosen = normal.isEmpty() ? fallback : normal;
            int n = chosen.size();
            LootTable.Entry[] entries = chosen.toArray(LootTable.Entry[]::new);
            double[] weights = new double[n], cumulative = new double[n];
            double total = 0;
            for (int i = 0; i < n; i++) {
                double w = entries[i].weight(source);
                if (luckPoints != 0) w *= Math.pow(base, entries[i].rarity(source) / 10000.0);
                weights[i] = w;
                total += w;
                cumulative[i] = total;
            }
            return new Candidates(entries, weights, cumulative, total);
        }

        boolean eligible(LootTable.Entry entry, int minW, boolean filtered) {
            String item = entry.item().toLowerCase(Locale.ROOT);
            if (!entry.options().conditions().test(ctx)) return false;
            if (!item.isEmpty() && exclude.contains(item)) return false;
            if (entry.tableRef()) {
                String ref = refId(entry);
                if (ref.isBlank()) return false;
                try {
                    return LootTables.find(ref).isPresent() && !exclude.contains(LootTable.TABLE_PREFIX + ref.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException invalid) { return false; }
            }
            if (item.isEmpty()) return !filtered;
            if (unique && used.contains(item)) return false;
            int rarity = entry.rarity(source);
            return rarity >= minW && rarity <= maxWeight;
        }
    }

    private record Candidates(LootTable.Entry[] entries, double[] weights, double[] cumulative, double total) {}
}
