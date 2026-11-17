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
 * Player loot luck: the vanilla Luck attribute (Luck potion, Bad Omen of luck items) plus every registered source,
 * e.g. a Skills talent or an Items affix. Sources are called on the server thread; one failing source only counts 0.
 * Removed automatically when the owner is disabled. @since 0.44.0
 * <p>Preferred access: {@code api.loot().luck()}.
 */
public final class LootLuck {
    private static final Map<Plugin, ToDoubleFunction<Player>> SOURCES = new ConcurrentHashMap<>();
    /** Each source is clamped to this many luck points. */
    public static final double MAX_PER_SOURCE = 10;
    private LootLuck() {}

    /** Registers (or replaces) the luck source of a plugin. */
    public static void register(Plugin owner, ToDoubleFunction<Player> source) {
        SOURCES.put(Objects.requireNonNull(owner), Objects.requireNonNull(source));
    }

    public static void release(Plugin owner) { if (owner != null) SOURCES.remove(owner); }

    /** Luck attribute plus all sources. Server thread. */
    public static double of(Player player) {
        if (player == null) return 0;
        double luck = 0;
        try {
            var attribute = player.getAttribute(Vystorm.vystorm_core.compat.ServerCompat.attribute("LUCK", "GENERIC_LUCK"));
            if (attribute != null) luck += attribute.getValue();
        } catch (RuntimeException | LinkageError ignored) { }
        for (var entry : SOURCES.entrySet()) {
            if (!entry.getKey().isEnabled()) continue;
            try {
                double value = entry.getValue().applyAsDouble(player);
                if (Double.isFinite(value)) luck += Math.max(-MAX_PER_SOURCE, Math.min(MAX_PER_SOURCE, value));
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("LootLuck", entry.getKey().getName(), "luck", failure);
            }
        }
        return Double.isFinite(luck) ? luck : 0;
    }
}
