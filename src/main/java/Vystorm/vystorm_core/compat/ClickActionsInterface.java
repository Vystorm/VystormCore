package Vystorm.vystorm_core.compat;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ClickActions}, reached through {@code api.compat().clicks()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClickActions}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClickActionsInterface {
    /** The delegating default. Plugins use {@code api.compat().clicks()}. */
    ClickActionsInterface DEFAULT = new ClickActionsInterface() {};

    /** See {@link ClickActions#callback}. */
    default ClickEvent callback(Consumer<Player> action, int uses, Duration lifetime) { return ClickActions.callback(action, uses, lifetime); }

    /** Owner-bound callback; disabled providers cannot execute and are released by CoreLifecycle. */
    default ClickEvent callback(Plugin provider, Consumer<Player> action, int uses, Duration lifetime) {
        return ClickActions.callback(provider, action, uses, lifetime);
    }

    /** See {@link ClickActions#release}. */
    default void release(Plugin provider) { ClickActions.release(provider); }
}
