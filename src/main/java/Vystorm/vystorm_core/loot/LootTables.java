package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.item.ItemRefs;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Shared loot registry. Registration, generation and release on the server thread. @since 0.34.0
 * <p>Preferred access: {@code api.loot()}.
 */
public final class LootTables {
    private record Registered(Plugin owner, LootTable table) {}
    private static volatile Map<String, Registered> tables = Map.of();
    private LootTables() {}

    /** Atomically replaces this owner's tables; conflicting names from other owners are rejected. */
    public static synchronized void replace(Plugin owner, Map<String, LootTable> definitions) {
        Objects.requireNonNull(owner);
        Map<String, Registered> next = new LinkedHashMap<>(tables);
        next.entrySet().removeIf(e -> e.getValue().owner() == owner);
        for (var entry : definitions.entrySet()) {
            String key = key(entry.getKey());
            if (next.putIfAbsent(key, new Registered(owner, Objects.requireNonNull(entry.getValue()))) != null)
                throw new IllegalArgumentException("Duplicate loot table: " + key);
        }
        tables = Map.copyOf(next);
    }

    public static synchronized void release(Plugin owner) {
        Map<String, Registered> next = new HashMap<>(tables);
        next.entrySet().removeIf(e -> e.getValue().owner() == owner);
        tables = Map.copyOf(next);
    }

    public static Optional<LootTable> find(String id) {
        Registered entry = tables.get(key(id));
        return entry == null || !entry.owner().isEnabled() ? Optional.empty() : Optional.of(entry.table());
    }

    /**
     * Per-source override: returns {@code configured} when it names a registered table or category, otherwise
     * {@code fallback} (the plugin's default category). Blank or invalid ids count as "not configured".
     * @since 0.44.0
     */
    public static String choose(String configured, String fallback) {
        if (configured != null && !configured.isBlank()) {
            try { if (find(configured).isPresent()) return configured.trim(); }
            catch (IllegalArgumentException invalid) { /* invalid id: use fallback */ }
        }
        return fallback;
    }

    /** True when the id is a registered, enabled table. Invalid ids are false. @since 0.44.0 */
    public static boolean exists(String id) {
        try { return id != null && find(id).isPresent(); } catch (IllegalArgumentException invalid) { return false; }
    }

    /** Name of the plugin that registered the table, or empty. @since 0.44.0 */
    public static Optional<String> owner(String id) {
        try {
            Registered entry = tables.get(key(id));
            return entry == null ? Optional.empty() : Optional.of(entry.owner().getName());
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public static Set<String> ids() {
        Set<String> result = new TreeSet<>();
        tables.forEach((id, entry) -> { if (entry.owner().isEnabled()) result.add(id); });
        return Collections.unmodifiableSet(result);
    }

    /**
     * Resolves selected items through ItemRefs, including native custom items. Never grants items itself.
     * Missing tables/items fail explicitly: callers must not substitute a second random roll.
     * Each unit is resolved separately so unstackable custom items retain independent identities.
     */
    public static List<ItemStack> generate(String id, LootTable.Source source, RandomGenerator random) {
        LootTable table = find(id).orElseThrow(() -> new IllegalArgumentException("Unknown loot table: " + id));
        List<ItemStack> result = new ArrayList<>();
        for (var drop : table.roll(source, random)) {
            for (int n = 0; n < drop.amount(); n++) {
                ItemStack item = ItemRefs.resolve(drop.item(), 1)
                        .orElseThrow(() -> new IllegalStateException("Unresolved loot item: " + drop.item()));
                if (Set.of(org.bukkit.Material.AIR, org.bukkit.Material.CAVE_AIR, org.bukkit.Material.VOID_AIR).contains(item.getType())
                        || item.getAmount() != 1) throw new IllegalStateException("Invalid loot item: " + drop.item());
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static String key(String id) {
        String key = Objects.requireNonNull(id).trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Loot table requires namespace:id: " + id);
        return key;
    }
}
