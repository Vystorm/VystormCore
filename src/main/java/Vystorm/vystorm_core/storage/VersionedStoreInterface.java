package Vystorm.vystorm_core.storage;

import Vystorm.vystorm_core.API;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.java.JavaPlugin;
import Vystorm.vystorm_core.storage.VersionedStore.*;

/**
 * Manager view of {@link VersionedStore}, reached through {@code api.data().versioned()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link VersionedStore}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface VersionedStoreInterface {
    /** The delegating default. Plugins use {@code api.data().versioned()}. */
    VersionedStoreInterface DEFAULT = new VersionedStoreInterface() {};

    /** See {@link VersionedStore#open}. */
    default <T> VersionedStore<T> open(JavaPlugin plugin, API core, String folder, String file, Class<T> type, int schema, Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty) throws Exception {
        return VersionedStore.open(plugin, core, folder, file, type, schema, migrations, empty);
    }

    /** Additive overload for lifecycle-owned modules with separate data folders. @since 0.31.0 */
    default <T> VersionedStore<T> open(org.bukkit.plugin.Plugin plugin, API core, String folder, String file, Class<T> type, int schema, Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty) throws Exception {
        return VersionedStore.open(plugin, core, folder, file, type, schema, migrations, empty);
    }
}
