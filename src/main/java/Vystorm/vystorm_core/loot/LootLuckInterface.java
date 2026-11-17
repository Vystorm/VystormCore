package Vystorm.vystorm_core.loot;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link LootLuck}, reached through {@code api.loot().luck()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link LootLuck}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface LootLuckInterface {
    /** The delegating default. Plugins use {@code api.loot().luck()}. */
    LootLuckInterface DEFAULT = new LootLuckInterface() {};

    /** Registers (or replaces) the luck source of a plugin. */
    default void register(Plugin owner, ToDoubleFunction<Player> source) { LootLuck.register(owner, source); }

    /** See {@link LootLuck#release}. */
    default void release(Plugin owner) { LootLuck.release(owner); }

    /** Luck attribute plus all sources. Server thread. */
    default double of(Player player) { return LootLuck.of(player); }
}
