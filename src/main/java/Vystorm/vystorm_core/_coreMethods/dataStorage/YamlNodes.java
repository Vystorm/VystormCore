package Vystorm.vystorm_core._coreMethods.dataStorage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Drops the SnakeYAML node cache a loaded {@link YamlConfiguration} keeps.
 *
 * <p>Bukkit builds the configuration from the composed node tree by calling its constructor once per key and value
 * ({@code YamlConstructor.construct(Node)}). SnakeYAML's {@code BaseConstructor} remembers every node it constructed
 * in {@code constructedObjects} and normally clears that map after each document; that single-node path never does.
 * So every {@code YamlConfiguration} that stays in memory after {@code load} also keeps two {@code ScalarNode}s per
 * entry plus their start/end {@code Mark}s (about 160 bytes per entry), and reloading the same object adds another set
 * each time. Core keeps its data entries for the whole server run; on a live server a 270,000-chunk data set held this
 * way cost about 500 MB of nodes (30.10.2026).</p>
 *
 * <p>{@link #release} empties those caches, which is what SnakeYAML itself does at the end of a document. Nothing that
 * was loaded changes. If a future Bukkit/SnakeYAML lays its fields out differently, it does nothing.</p>
 *
 * @since 0.45.0
 */
public final class YamlNodes {
    private static final Field CONSTRUCTOR;
    private static final List<Field> CACHES = new ArrayList<>();

    static {
        Field constructor = null;
        try {
            constructor = YamlConfiguration.class.getDeclaredField("constructor");
            constructor.setAccessible(true);
            Class<?> base = Class.forName("org.yaml.snakeyaml.constructor.BaseConstructor", false, YamlConfiguration.class.getClassLoader());
            for (String name : new String[]{"constructedObjects", "recursiveObjects", "maps2fill", "sets2fill"}) {
                try {
                    Field cache = base.getDeclaredField(name);
                    cache.setAccessible(true);
                    CACHES.add(cache);
                } catch (NoSuchFieldException ignored) {
                    // Older/newer SnakeYAML without this cache.
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            constructor = null;
            CACHES.clear();
        }
        CONSTRUCTOR = constructor;
    }

    private YamlNodes() {}

    /** Whether this server's Bukkit/SnakeYAML exposes the cache (diagnostics and tests). */
    public static boolean supported() { return CONSTRUCTOR != null && !CACHES.isEmpty(); }

    /** Empties the node caches of {@code configuration}; returns it. Safe to call on any configuration, also null. */
    public static <T> T release(T configuration) {
        if (!(configuration instanceof YamlConfiguration yaml) || !supported()) return configuration;
        try {
            Object constructor = CONSTRUCTOR.get(yaml);
            if (constructor == null) return configuration;
            for (Field cache : CACHES) {
                Object value = cache.get(constructor);
                if (value instanceof Map<?, ?> map) map.clear();
                else if (value instanceof Collection<?> collection) collection.clear();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Never fail a load over a cache.
        }
        return configuration;
    }

    /** Number of nodes {@code configuration} currently caches (tests and diagnostics); -1 when unknown. */
    public static int cached(YamlConfiguration configuration) {
        if (!supported()) return -1;
        try {
            Object constructor = CONSTRUCTOR.get(configuration);
            for (Field cache : CACHES)
                if (cache.getName().equals("constructedObjects") && cache.get(constructor) instanceof Map<?, ?> map) return map.size();
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return -1;
    }
}
