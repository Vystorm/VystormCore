package Vystorm.vystorm_core.lifecycle;

import java.util.*;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.lifecycle.FeatureDiagnostics.*;

/**
 * Manager view of {@link FeatureDiagnostics}, reached through {@code api.admin().diagnostics()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link FeatureDiagnostics}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface FeatureDiagnosticsInterface {
    /** The delegating default. Plugins use {@code api.admin().diagnostics()}. */
    FeatureDiagnosticsInterface DEFAULT = new FeatureDiagnosticsInterface() {};

    /** See {@link FeatureDiagnostics#register}. */
    default void register(Plugin owner, String id, Function<Player, Status> check) { FeatureDiagnostics.register(owner, id, check); }

    /** See {@link FeatureDiagnostics#inspect}. */
    default List<Status> inspect(Player target) { return FeatureDiagnostics.inspect(target); }

    /** See {@link FeatureDiagnostics#unregister}. */
    default void unregister(Plugin owner) { FeatureDiagnostics.unregister(owner); }
}
