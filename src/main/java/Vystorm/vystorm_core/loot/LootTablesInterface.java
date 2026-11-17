package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.item.ItemRefs;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link LootTables}, reached through {@code api.loot()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link LootTables}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface LootTablesInterface {
    /** The delegating default. Plugins use {@code api.loot()}. */
    LootTablesInterface DEFAULT = new LootTablesInterface() {};

    /** Atomically replaces this owner's tables; conflicting names from other owners are rejected. */
    default void replace(Plugin owner, Map<String, LootTable> definitions) { LootTables.replace(owner, definitions); }

    /** See {@link LootTables#release}. */
    default void release(Plugin owner) { LootTables.release(owner); }

    /** See {@link LootTables#find}. */
    default Optional<LootTable> find(String id) { return LootTables.find(id); }

    /**
     * Per-source override: returns {@code configured} when it names a registered table or category, otherwise
     * {@code fallback} (the plugin's default category). Blank or invalid ids count as "not configured".
     * @since 0.44.0
     */
    default String choose(String configured, String fallback) { return LootTables.choose(configured, fallback); }

    /** True when the id is a registered, enabled table. Invalid ids are false. @since 0.44.0 */
    default boolean exists(String id) { return LootTables.exists(id); }

    /** Name of the plugin that registered the table, or empty. @since 0.44.0 */
    default Optional<String> owner(String id) { return LootTables.owner(id); }

    /** See {@link LootTables#ids}. */
    default Set<String> ids() { return LootTables.ids(); }

    /**
     * Resolves selected items through ItemRefs, including native custom items. Never grants items itself.
     * Missing tables/items fail explicitly: callers must not substitute a second random roll.
     * Each unit is resolved separately so unstackable custom items retain independent identities.
     */
    default List<ItemStack> generate(String id, LootTable.Source source, RandomGenerator random) { return LootTables.generate(id, source, random); }
}
