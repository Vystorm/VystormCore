package Vystorm.vystorm_core.loot;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manager view of {@link LootTableConfig}, reached through {@code api.loot().config()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link LootTableConfig}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface LootTableConfigInterface {
    /** The delegating default. Plugins use {@code api.loot().config()}. */
    LootTableConfigInterface DEFAULT = new LootTableConfigInterface() {};

    /** Loads the owner's {@code loot-tables.yml} (unchanged 0.34 behaviour for plugins). */
    default void reload(JavaPlugin owner) throws Exception { LootTableConfig.reload(owner); }

    /**
     * Copies missing files from the plugin jar, parses all of them and atomically replaces the owner's tables.
     * Nothing changes when one file is invalid. Missing files without a bundled default are skipped.
     * @since 0.44.0
     */
    default Map<String, LootTable> load(JavaPlugin owner, String... files) throws Exception { return LootTableConfig.load(owner, files); }

    /** See {@link LootTableConfig#parse}. */
    default Map<String, LootTable> parse(ConfigurationSection yaml) { return LootTableConfig.parse(yaml); }

    /** Parses one table section. @since 0.44.0 */
    default LootTable table(String id, ConfigurationSection table) { return LootTableConfig.table(id, table); }
}
