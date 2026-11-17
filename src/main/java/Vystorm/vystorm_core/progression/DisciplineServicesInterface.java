package Vystorm.vystorm_core.progression;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link DisciplineServices}, reached through {@code api.player().disciplines()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link DisciplineServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface DisciplineServicesInterface {
    /** The delegating default. Plugins use {@code api.player().disciplines()}. */
    DisciplineServicesInterface DEFAULT = new DisciplineServicesInterface() {};

    /** See {@link DisciplineServices#register}. */
    default void register(Plugin owner, DisciplineSource source) { DisciplineServices.register(owner, source); }

    /** See {@link DisciplineServices#unregister}. */
    default void unregister(Plugin owner) { DisciplineServices.unregister(owner); }

    /** See {@link DisciplineServices#available}. */
    default boolean available() { return DisciplineServices.available(); }

    /** See {@link DisciplineServices#disciplines}. */
    default Set<String> disciplines() { return DisciplineServices.disciplines(); }

    /** See {@link DisciplineServices#level}. */
    default int level(UUID player, String discipline) { return DisciplineServices.level(player, discipline); }

    /** See {@link DisciplineServices#modifier}. */
    default double modifier(UUID player, String key) { return DisciplineServices.modifier(player, key); }

    /** See {@link DisciplineServices#hasUnlock}. */
    default boolean hasUnlock(UUID player, String unlock) { return DisciplineServices.hasUnlock(player, unlock); }

    /** See {@link DisciplineServices#awardXp}. */
    default double awardXp(Player player, String discipline, double amount, String source) {
        return DisciplineServices.awardXp(player, discipline, amount, source);
    }
}
