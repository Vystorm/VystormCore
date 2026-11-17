package Vystorm.vystorm_core.loot;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Strict, atomic loader for loot table files. @since 0.34.0
 *
 * <p>Since 0.44.0: Core loads {@code loot-tables.yml}, {@code loot-vanilla.yml} and {@code loot-categories.yml};
 * other plugins load their own files with {@link #load(JavaPlugin, String...)}. A file may hold a {@code tables}
 * and/or a {@code categories} section; category keys without a namespace become {@code category:<key>}.
 * <p>Preferred access: {@code api.loot().config()}.
 */
public final class LootTableConfig {
    private LootTableConfig() {}
    /** Core's own files, in load order. @since 0.44.0 */
    public static final List<String> CORE_FILES = List.of("loot-tables.yml", "loot-vanilla.yml", "loot-mobs.yml", "loot-categories.yml");
    private static final Set<String> ENVIRONMENTS = Set.of("normal", "nether", "the_end", "custom");

    /** Loads the owner's {@code loot-tables.yml} (unchanged 0.34 behaviour for plugins). */
    public static void reload(JavaPlugin owner) throws Exception {
        load(owner, "loot-tables.yml");
    }

    /** Loads Core's loot files ({@link #CORE_FILES}). @since 0.44.0 */
    public static void reloadCore(JavaPlugin core) throws Exception {
        load(core, CORE_FILES.toArray(String[]::new));
    }

    /**
     * Copies missing files from the plugin jar, parses all of them and atomically replaces the owner's tables.
     * Nothing changes when one file is invalid. Missing files without a bundled default are skipped.
     * @since 0.44.0
     */
    public static Map<String, LootTable> load(JavaPlugin owner, String... files) throws Exception {
        Map<String, LootTable> merged = new LinkedHashMap<>();
        for (String name : files) {
            File file = new File(owner.getDataFolder(), name);
            if (!file.exists()) {
                if (owner.getResource(name) == null) continue;
                owner.saveResource(name, false);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            for (var entry : parse(yaml).entrySet())
                if (merged.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IllegalArgumentException(name + ": loot table " + entry.getKey() + " is defined twice");
        }
        LootTables.replace(owner, merged);
        return Map.copyOf(merged);
    }

    public static Map<String, LootTable> parse(ConfigurationSection yaml) {
        var root = yaml.getConfigurationSection("tables");
        var categories = yaml.getConfigurationSection("categories");
        if (root == null && categories == null) throw new IllegalArgumentException("Missing tables section");
        Map<String, LootTable> result = new LinkedHashMap<>();
        if (root != null) for (String id : root.getKeys(false)) result.put(id, table(id, root.getConfigurationSection(id)));
        if (categories != null) for (String key : categories.getKeys(false)) {
            String id = key.contains(":") ? key : "category:" + key;
            if (result.put(id, table(id, categories.getConfigurationSection(key))) != null)
                throw new IllegalArgumentException("Loot table " + id + " is defined twice");
        }
        return Map.copyOf(result);
    }

    /** Parses one table section. @since 0.44.0 */
    public static LootTable table(String id, ConfigurationSection table) {
        if (table == null) throw new IllegalArgumentException("Invalid table " + id);
        List<LootTable.Entry> entries = new ArrayList<>();
        Object raw = table.get("entries");
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException(id + ": entries must be a list");
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> entry)) throw new IllegalArgumentException(id + ": invalid entry");
            try { entries.add(entry(entry)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(id + ": " + invalid.getMessage(), invalid); }
        }
        int rolls = integer(table.get("rolls"), 1);
        return new LootTable(integer(table.get("min-rolls"), rolls), integer(table.get("max-rolls"), rolls),
                number(table.get("chance"), 1), entries, mode(id, table.get("mode")));
    }

    private static LootTable.Entry entry(Map<?, ?> entry) {
        Object item = entry.get("item");
        Object ref = entry.get("table");
        if (ref != null) {
            if (item != null) throw new IllegalArgumentException("use either item or table");
            if (!(ref instanceof String text) || text.isBlank()) throw new IllegalArgumentException("table must be a table id");
            item = LootTable.TABLE_PREFIX + text.trim();
        }
        if (!(item instanceof String)) throw new IllegalArgumentException("item must be a string (empty for no drop)");
        int weight = integer(entry.get("weight"), 0);
        int amount = integer(entry.get("amount"), 1);
        int[] enchant = range(entry.get("enchant-levels"));
        Object potion = entry.get("potion");
        if (potion != null) {
            if (!(potion instanceof String p)) throw new IllegalArgumentException("potion must be a potion type name");
            try { org.bukkit.potion.PotionType.valueOf(p.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("unknown potion type " + p); }
        }
        Set<String> environments = strings(entry.get("environments"));
        for (String env : environments) if (!ENVIRONMENTS.contains(env.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("environment must be one of " + ENVIRONMENTS + ": " + env);
        var conditions = new LootTable.Conditions(signed(entry.get("min-y")), signed(entry.get("max-y")), environments,
                strings(entry.get("biomes")), optionalNumber(entry.get("min-difficulty")), optionalNumber(entry.get("max-difficulty")));
        var options = new LootTable.Options(enchant[0], enchant[1], bool(entry.get("treasure-enchants")),
                potion == null ? "" : (String) potion, conditions, bool(entry.get("fallback")), number(entry.get("chance"), 1));
        return new LootTable.Entry((String) item, integer(entry.get("min-amount"), amount), integer(entry.get("max-amount"), amount),
                integer(entry.get("mob-weight"), weight), integer(entry.get("chest-weight"), weight), options);
    }

    private static int[] range(Object value) {
        if (value == null) return new int[]{0, 0};
        if (value instanceof String text && text.matches("\\s*\\d+\\s*-\\s*\\d+\\s*")) {
            String[] parts = text.split("-");
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        }
        if (value instanceof List<?> list && list.size() == 2) return new int[]{integer(list.get(0), 0), integer(list.get(1), 0)};
        int single = integer(value, 0);
        return new int[]{single, single};
    }
    private static boolean mode(String id, Object value) {
        if (value == null || "weighted".equals(value)) return false;
        if ("each".equals(value)) return true;
        throw new IllegalArgumentException(id + ": mode must be weighted or each");
    }
    private static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException("Expected true/false: " + value);
    }
    private static Set<String> strings(Object value) {
        if (value == null) return Set.of();
        if (value instanceof String text) return Set.of(text);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list: " + value);
        Set<String> out = new LinkedHashSet<>();
        for (Object v : list) out.add(String.valueOf(v));
        return out;
    }
    private static Integer signed(Object value) {
        if (value == null) return null;
        double n = number(value, 0);
        if (n != Math.rint(n) || Math.abs(n) > 100000) throw new IllegalArgumentException("Expected integer: " + value);
        return (int) n;
    }
    private static Double optionalNumber(Object value) { return value == null ? null : number(value, 0); }
    private static double number(Object value, double fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Expected finite number: " + value);
        return n.doubleValue();
    }
    private static int integer(Object value, int fallback) {
        double n = number(value, fallback);
        if (n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("Expected non-negative integer: " + value);
        return (int)n;
    }
}
