package Vystorm.vystorm_core.screen;

import Vystorm.vystorm_core.screen.internal.HudRuntime;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link Huds}, reached through {@code api.gui().huds()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Huds}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface HudsInterface {
    /** The delegating default. Plugins use {@code api.gui().huds()}. */
    HudsInterface DEFAULT = new HudsInterface() {};

    /** See {@link Huds#of}. */
    default Huds of(Plugin owner) { return Huds.of(owner); }
}
