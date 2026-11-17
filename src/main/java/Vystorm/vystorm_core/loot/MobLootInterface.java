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
import Vystorm.vystorm_core.loot.MobLoot.*;

/**
 * Manager view of {@link MobLoot}, reached through {@code api.loot().mobs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MobLoot}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MobLootInterface {
    /** The delegating default. Plugins use {@code api.loot().mobs()}. */
    MobLootInterface DEFAULT = new MobLootInterface() {};

    /** See {@link MobLoot#settings}. */
    default Settings settings() { return MobLoot.settings(); }

    /** Luck points of a mob level with the current settings. */
    default double luck(double level) { return MobLoot.luck(level); }

    /** Luck points of a mob level: {@code min(max, level × perLevel)}; level ≤ 0 or invalid = 0. Pure. */
    default double luck(double level, Settings s) { return MobLoot.luck(level, s); }

    /** Pity tier of a rank, or {@code null}. */
    default LootTier pity(Rank rank) { return MobLoot.pity(rank); }

    /** Core table of a vanilla mob type: {@code vystorm_core:mob/<type>}, else {@link MobLoot#DEFAULT_TABLE}, else blank (category fallback). */
    default String table(EntityType type) { return MobLoot.table(type); }

    /** {@link #table(EntityType)} by entity key ({@code zombie}, {@code minecraft:zombie}). */
    default String table(String entityKey) { return MobLoot.table(entityKey); }

    /** Marks a spawned entity as owned by {@code owner}: other plugins do not roll its kill loot. Server thread. */
    default void claim(Plugin owner, Entity entity) { MobLoot.claim(owner, entity); }

    /** Plugin that rolls this mob's drops: a {@link #claim} or the Core mob service provider (VystormMobs). Server thread. */
    default Optional<String> owner(Entity entity) { return MobLoot.owner(entity); }

    /** True when another enabled plugin owns the mob (so {@code self} must not roll it). */
    default boolean ownedByOther(Plugin self, Entity entity) { return MobLoot.ownedByOther(self, entity); }

    /** Registers a level source (SpawnDirector monster level, event tiers ...); empty = unknown. Released on disable. */
    default void levels(Plugin owner, Function<Entity, OptionalDouble> provider) { MobLoot.levels(owner, provider); }

    /** Mob level: Core mob service (VystormMobs) first, else the highest registered source, else 0. Server thread. */
    default double level(Entity entity) { return MobLoot.level(entity); }

    /** Rank of a mob from the Core mob service (VystormMobs tier), else {@code fallback}. Server thread. */
    default Rank rank(Entity entity, Rank fallback) { return MobLoot.rank(entity, fallback); }

    /** Records that this kill's loot is rolled. {@code false} when it already was (the caller must not roll again). */
    default boolean markRolled(Entity entity) { return MobLoot.markRolled(entity); }

    /** True when the kill loot of this entity was rolled in the last seconds. */
    default boolean rolled(Entity entity) { return MobLoot.rolled(entity); }

    /** Registers (or replaces) a plugin's extra mob drop part. Released on disable. */
    default void contribute(Plugin owner, Contribution contribution) { MobLoot.contribute(owner, contribution); }

    /** See {@link MobLoot#release}. */
    default void release(Plugin owner) { MobLoot.release(owner); }

    /**
     * Items of every other plugin's {@link Contribution} for this kill, each own table rolled once with the kill's level
     * luck. {@code roller} (the calling plugin) is skipped. Server thread.
     */
    default List<ItemStack> extras(Plugin roller, Kill kill, Consumer<String> problems) { return MobLoot.extras(roller, kill, problems); }

    /**
     * The whole kill loot of one mob for its owner: {@code configured} (per-source override) or the rank's category with
     * {@code self} as the mob's own table, luck from {@code level} only, the rank's pity, plus {@link #extras}. Returns
     * {@link Drop#NONE} when the kill was already rolled. Server thread.
     */
    default Drop drop(Plugin roller, LivingEntity entity, String configured, String self, double level, Rank rank, Player killer) {
        return MobLoot.drop(roller, entity, configured, self, level, rank, killer);
    }
}
