package Vystorm.vystorm_core.storage;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.storage.MigrationPreviews.*;

/**
 * Manager view of {@link MigrationPreviews}, reached through {@code api.data().migrationPreviews()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MigrationPreviews}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MigrationPreviewsInterface {
    /** The delegating default. Plugins use {@code api.data().migrationPreviews()}. */
    MigrationPreviewsInterface DEFAULT = new MigrationPreviewsInterface() {};

    /** See {@link MigrationPreviews#register}. */
    default void register(Plugin owner, String id, Path source, Converter converter) { MigrationPreviews.register(owner, id, source, converter); }

    /** See {@link MigrationPreviews#keys}. */
    default List<String> keys() { return MigrationPreviews.keys(); }

    /** See {@link MigrationPreviews#run}. */
    default Report run(String id) { return MigrationPreviews.run(id); }

    /** See {@link MigrationPreviews#unregister}. */
    default void unregister(Plugin owner) { MigrationPreviews.unregister(owner); }
}
