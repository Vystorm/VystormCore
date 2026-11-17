package Vystorm.vystorm_core.progression;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link CharacterServices}, reached through {@code api.player().character()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CharacterServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CharacterServicesInterface {
    /** The delegating default. Plugins use {@code api.player().character()}. */
    CharacterServicesInterface DEFAULT = new CharacterServicesInterface() {};

    /** See {@link CharacterServices#register}. */
    default void register(Plugin owner, CharacterSource source) { CharacterServices.register(owner, source); }

    /** See {@link CharacterServices#unregister}. */
    default void unregister(Plugin owner) { CharacterServices.unregister(owner); }

    /** See {@link CharacterServices#available}. */
    default boolean available() { return CharacterServices.available(); }

    /** See {@link CharacterServices#level}. */
    default int level(UUID player) { return CharacterServices.level(player); }

    /** See {@link CharacterServices#maxLevel}. */
    default int maxLevel() { return CharacterServices.maxLevel(); }

    /** See {@link CharacterServices#awardXp}. */
    default double awardXp(Player player, double amount, String source, int sourceLevel) {
        return CharacterServices.awardXp(player, amount, source, sourceLevel);
    }
}
