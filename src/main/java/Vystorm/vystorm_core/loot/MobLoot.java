package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.mob.MobTier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Mob drops, one rule for every Vystorm plugin: <b>what</b> drops is defined by the mob (its own table: a custom mob's
 * table, or {@code vystorm_core:mob/<entity type>} for vanilla mobs), <b>how good</b> it is depends only on the mob's
 * level ({@link #luck(double)}); player luck never changes mob drops. Elite and boss ranks may add pity
 * ({@code loot.mob-pity}, boss: rare+ by default). Capability {@code mob-loot}. @since 0.44.0
 *
 * <p>Precedence, so one kill is never rolled twice:
 * <ol>
 *     <li>the plugin that owns the mob rolls it: VystormMobs for its mobs (Core mob service), event and dungeon plugins
 *         for the mobs they spawn ({@link #claim(Plugin, Entity)} at spawn time);</li>
 *     <li>SpawnDirector rolls the remaining (vanilla/director) mobs with {@link #table(EntityType)};</li>
 *     <li>plugins that only add items to mob drops (VystormItems gear) {@link #contribute} an extra part that the rolling
 *         plugin includes ({@link #extras}); they roll themselves only when nobody else rolled the kill
 *         ({@link #rolled(Entity)}, {@link #ownedByOther(Plugin, Entity)}).</li>
 * </ol>
 * Every roller calls {@link #markRolled(Entity)} (done by {@link #drop}) so a second roller skips the kill.
 * <p>Preferred access: {@code api.loot().mobs()}.
 */
public final class MobLoot {
    /** Prefix of Core's vanilla mob tables ({@code loot-mobs.yml}). */
    public static final String TABLE_PREFIX = "vystorm_core:mob/";
    /** Table for vanilla mob types without an own table. */
    public static final String DEFAULT_TABLE = TABLE_PREFIX + "default";
    /** PDC key on entities spawned by a plugin that rolls their drops itself (value: plugin name). */
    public static final NamespacedKey OWNER_KEY = new NamespacedKey("vystorm_core", "loot_owner");

    /** Mob rank: picks the mob drop category and the pity. */
    public enum Rank {
        COMMON, ELITE, BOSS;

        /** {@code category:mob_drop_common}, {@code _elite} or {@code _boss}. */
        public String category() { return "category:mob_drop_" + name().toLowerCase(Locale.ROOT); }

        /** NORMAL/VETERAN/RARE = common, ELITE/CHAMPION = elite, BOSS/WORLD_BOSS = boss. */
        public static Rank of(MobTier tier) {
            if (tier == null) return COMMON;
            return switch (tier) {
                case BOSS, WORLD_BOSS -> BOSS;
                case ELITE, CHAMPION -> ELITE;
                default -> COMMON;
            };
        }
    }

    /**
     * Level formula and pity: {@code luck = min(maxLuck, level × perLevel)}, level ≤ 0 = no luck.
     * Config: {@code loot.mob-level-luck.per-level} (0.05), {@code .max} (3.0), {@code loot.mob-pity.<rank>} (none/none/rare).
     */
    public record Settings(double perLevel, double maxLuck, LootTier commonPity, LootTier elitePity, LootTier bossPity) {
        public static final Settings DEFAULT = new Settings(0.05, 3.0, null, null, LootTier.RARE);
        public Settings {
            perLevel = Double.isFinite(perLevel) ? Math.max(0, Math.min(1, perLevel)) : DEFAULT.perLevel;
            maxLuck = Double.isFinite(maxLuck) ? Math.max(0, Math.min(20, maxLuck)) : DEFAULT.maxLuck;
        }

        /** Reads {@code loot.mob-level-luck} and {@code loot.mob-pity} from Core's config; missing keys keep the defaults. */
        public static Settings of(ConfigurationSection config) {
            if (config == null) return DEFAULT;
            return new Settings(config.getDouble("loot.mob-level-luck.per-level", DEFAULT.perLevel),
                    config.getDouble("loot.mob-level-luck.max", DEFAULT.maxLuck),
                    pity(config.getString("loot.mob-pity.common", "none"), DEFAULT.commonPity),
                    pity(config.getString("loot.mob-pity.elite", "none"), DEFAULT.elitePity),
                    pity(config.getString("loot.mob-pity.boss", "rare"), DEFAULT.bossPity));
        }

        private static LootTier pity(String value, LootTier fallback) {
            if (value == null || value.isBlank()) return fallback;
            if (value.trim().equalsIgnoreCase("none") || value.trim().equalsIgnoreCase("false")) return null;
            return LootTier.parse(value).orElse(fallback);
        }
    }

    /** One kill as seen by {@link Contribution}s. {@code killer} may be null. */
    public record Kill(LivingEntity entity, String type, double level, Rank rank, Player killer, String roller) {}

    /**
     * Extra mob drop part of a plugin that does not own mobs (VystormItems gear). The rolling plugin rolls each returned
     * table once with the same level luck ({@link LootRoll#mobLevel}) and adds the items; no second category roll.
     */
    public interface Contribution {
        /** Own tables to roll for this kill (empty = nothing). Server thread. */
        List<String> tables(Kill kill);

        /** Creates the items of one own part (default: Core creates every hit). Server thread. */
        default List<ItemStack> items(Kill kill, LootResult part) {
            List<ItemStack> out = new ArrayList<>();
            for (LootResult.Hit hit : part.hits()) out.addAll(LootRoll.create(hit));
            return out;
        }
    }

    /** Result of {@link #drop}: all items (own part + extras) and the problems to log. */
    public record Drop(List<ItemStack> items, LootResult main, List<String> problems) {
        public static final Drop NONE = new Drop(List.of(), null, List.of());
    }

    private static volatile Settings settings = Settings.DEFAULT;
    private static final Map<Plugin, Contribution> CONTRIBUTIONS = new ConcurrentHashMap<>();
    private static final Map<Plugin, Function<Entity, OptionalDouble>> LEVELS = new ConcurrentHashMap<>();
    /** Entities whose kill loot was rolled, with the time (ms); entries expire after {@link #ROLLED_TTL_MS}. */
    private static final Map<UUID, Long> ROLLED = new ConcurrentHashMap<>();
    private static final long ROLLED_TTL_MS = 10_000;

    private MobLoot() {}

    public static Settings settings() { return settings; }

    /** Applies new settings (Core calls this on enable and on config reload). */
    public static void configure(Settings value) { settings = value == null ? Settings.DEFAULT : value; }

    /** Luck points of a mob level with the current settings. */
    public static double luck(double level) { return luck(level, settings); }

    /** Luck points of a mob level: {@code min(max, level × perLevel)}; level ≤ 0 or invalid = 0. Pure. */
    public static double luck(double level, Settings s) {
        if (!Double.isFinite(level) || level <= 0 || s == null) return 0;
        return Math.min(s.maxLuck(), level * s.perLevel());
    }

    /** Pity tier of a rank, or {@code null}. */
    public static LootTier pity(Rank rank) {
        Settings s = settings;
        if (rank == null) return s.commonPity();
        return switch (rank) {
            case BOSS -> s.bossPity();
            case ELITE -> s.elitePity();
            case COMMON -> s.commonPity();
        };
    }

    /** Core table of a vanilla mob type: {@code vystorm_core:mob/<type>}, else {@link #DEFAULT_TABLE}, else blank (category fallback). */
    public static String table(EntityType type) {
        return type == null ? table("") : table(type.getKey().getKey());
    }

    /** {@link #table(EntityType)} by entity key ({@code zombie}, {@code minecraft:zombie}). */
    public static String table(String entityKey) {
        String key = entityKey == null ? "" : entityKey.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        if (!key.isEmpty() && LootTables.exists(TABLE_PREFIX + key)) return TABLE_PREFIX + key;
        return LootTables.exists(DEFAULT_TABLE) ? DEFAULT_TABLE : "";
    }

    // ------------------------------------------------------------------ ownership and level

    /** Marks a spawned entity as owned by {@code owner}: other plugins do not roll its kill loot. Server thread. */
    public static void claim(Plugin owner, Entity entity) {
        if (owner == null || entity == null) return;
        entity.getPersistentDataContainer().set(OWNER_KEY, PersistentDataType.STRING, owner.getName());
    }

    /** Plugin that rolls this mob's drops: a {@link #claim} or the Core mob service provider (VystormMobs). Server thread. */
    public static Optional<String> owner(Entity entity) {
        if (entity == null) return Optional.empty();
        try {
            String claimed = entity.getPersistentDataContainer().get(OWNER_KEY, PersistentDataType.STRING);
            if (claimed != null && !claimed.isBlank()) return Optional.of(claimed);
        } catch (RuntimeException | LinkageError ignored) { }
        try {
            var service = Vystorm.vystorm_core.mob.MobServices.service();
            if (service.isPresent() && service.get().byEntity(entity).isPresent())
                return Vystorm.vystorm_core.mob.MobServices.providerName();
        } catch (RuntimeException | LinkageError ignored) { }
        return Optional.empty();
    }

    /** True when another enabled plugin owns the mob (so {@code self} must not roll it). */
    public static boolean ownedByOther(Plugin self, Entity entity) {
        Optional<String> owner = owner(entity);
        if (owner.isEmpty() || (self != null && owner.get().equalsIgnoreCase(self.getName()))) return false;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(owner.get());
            return plugin != null && plugin.isEnabled();
        } catch (RuntimeException | LinkageError noServer) { return true; }
    }

    /** Registers a level source (SpawnDirector monster level, event tiers ...); empty = unknown. Released on disable. */
    public static void levels(Plugin owner, Function<Entity, OptionalDouble> provider) {
        LEVELS.put(Objects.requireNonNull(owner), Objects.requireNonNull(provider));
    }

    /** Mob level: Core mob service (VystormMobs) first, else the highest registered source, else 0. Server thread. */
    public static double level(Entity entity) {
        if (entity == null) return 0;
        try {
            var handle = Vystorm.vystorm_core.mob.MobServices.service().flatMap(s -> s.byEntity(entity));
            if (handle.isPresent()) return Math.max(0, handle.get().level());
        } catch (RuntimeException | LinkageError ignored) { }
        double best = 0;
        for (var entry : LEVELS.entrySet()) {
            if (!entry.getKey().isEnabled()) continue;
            try {
                OptionalDouble value = entry.getValue().apply(entity);
                if (value != null && value.isPresent() && Double.isFinite(value.getAsDouble())) best = Math.max(best, value.getAsDouble());
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("MobLoot", entry.getKey().getName(), "level", failure);
            }
        }
        return best;
    }

    /** Rank of a mob from the Core mob service (VystormMobs tier), else {@code fallback}. Server thread. */
    public static Rank rank(Entity entity, Rank fallback) {
        try {
            var handle = Vystorm.vystorm_core.mob.MobServices.service().flatMap(s -> s.byEntity(entity));
            if (handle.isPresent()) return Rank.of(handle.get().tier());
        } catch (RuntimeException | LinkageError ignored) { }
        return fallback == null ? Rank.COMMON : fallback;
    }

    /** Records that this kill's loot is rolled. {@code false} when it already was (the caller must not roll again). */
    public static boolean markRolled(Entity entity) {
        if (entity == null || entity.getUniqueId() == null) return true;
        long now = System.currentTimeMillis();
        if (ROLLED.size() > 256) ROLLED.values().removeIf(at -> now - at > ROLLED_TTL_MS);
        Long previous = ROLLED.putIfAbsent(entity.getUniqueId(), now);
        if (previous == null) return true;
        if (now - previous > ROLLED_TTL_MS) { ROLLED.put(entity.getUniqueId(), now); return true; }
        return false;
    }

    /** True when the kill loot of this entity was rolled in the last seconds. */
    public static boolean rolled(Entity entity) {
        if (entity == null || entity.getUniqueId() == null) return false;
        Long at = ROLLED.get(entity.getUniqueId());
        return at != null && System.currentTimeMillis() - at <= ROLLED_TTL_MS;
    }

    // ------------------------------------------------------------------ contributions and the full drop

    /** Registers (or replaces) a plugin's extra mob drop part. Released on disable. */
    public static void contribute(Plugin owner, Contribution contribution) {
        CONTRIBUTIONS.put(Objects.requireNonNull(owner), Objects.requireNonNull(contribution));
    }

    public static void release(Plugin owner) {
        if (owner == null) return;
        CONTRIBUTIONS.remove(owner);
        LEVELS.remove(owner);
    }

    /**
     * Items of every other plugin's {@link Contribution} for this kill, each own table rolled once with the kill's level
     * luck. {@code roller} (the calling plugin) is skipped. Server thread.
     */
    public static List<ItemStack> extras(Plugin roller, Kill kill, Consumer<String> problems) {
        List<ItemStack> out = new ArrayList<>();
        for (var entry : CONTRIBUTIONS.entrySet()) {
            Plugin owner = entry.getKey();
            if (owner == roller || !owner.isEnabled()) continue;
            try {
                for (String table : entry.getValue().tables(kill)) {
                    if (table == null || table.isBlank() || !LootTables.exists(table)) continue;
                    LootResult part = LootRoll.table(table).mobLevel(kill.level()).context(context(kill.entity())
                            .withSource(owner.getName())).roll();
                    if (problems != null) part.problems().forEach(problems);
                    out.addAll(entry.getValue().items(kill, part));
                }
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("MobLoot", owner.getName(), "contribution", failure);
            }
        }
        return out;
    }

    /**
     * The whole kill loot of one mob for its owner: {@code configured} (per-source override) or the rank's category with
     * {@code self} as the mob's own table, luck from {@code level} only, the rank's pity, plus {@link #extras}. Returns
     * {@link Drop#NONE} when the kill was already rolled. Server thread.
     */
    public static Drop drop(Plugin roller, LivingEntity entity, String configured, String self, double level, Rank rank, Player killer) {
        if (entity != null && !markRolled(entity)) return Drop.NONE;
        Rank r = rank == null ? Rank.COMMON : rank;
        RollContext context = context(entity).withSelf(self).withSource(roller == null ? "" : roller.getName());
        LootResult main = LootRoll.forMob(LootTables.choose(configured, r.category()), context, level, r).generate();
        List<ItemStack> items = new ArrayList<>(main.items());
        List<String> problems = new ArrayList<>(main.problems());
        if (entity != null) {
            Kill kill = new Kill(entity, entity.getType() == null ? "" : entity.getType().getKey().getKey(), level, r, killer, roller == null ? "" : roller.getName());
            items.addAll(extras(roller, kill, problems::add));
        }
        return new Drop(List.copyOf(items), main, List.copyOf(problems));
    }

    private static RollContext context(Entity entity) {
        try { return entity == null ? RollContext.NONE : RollContext.of(entity.getLocation()); }
        catch (RuntimeException | LinkageError noWorld) { return RollContext.NONE; }
    }
}
