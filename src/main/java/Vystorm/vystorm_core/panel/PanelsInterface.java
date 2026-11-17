package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core.panel.internal.PanelHub;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelPlatform;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link Panels}, reached through {@code api.gui().panels()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Panels}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PanelsInterface {
    /** The delegating default. Plugins use {@code api.gui().panels()}. */
    PanelsInterface DEFAULT = new PanelsInterface() {};

    /** See {@link Panels#of}. */
    default Panels of(Plugin plugin) { return Panels.of(plugin); }

    /** See {@link Panels#enabled}. */
    default boolean enabled() { return Panels.enabled(); }

    /** See {@link Panels#release}. */
    default void release(Plugin owner) { Panels.release(owner); }
}
