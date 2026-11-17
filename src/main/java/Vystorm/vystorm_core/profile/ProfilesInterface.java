package Vystorm.vystorm_core.profile;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.profile.Profiles.*;

/**
 * Manager view of {@link Profiles}, reached through {@code api.gui().profiles()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Profiles}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ProfilesInterface {
    /** The delegating default. Plugins use {@code api.gui().profiles()}. */
    ProfilesInterface DEFAULT = new ProfilesInterface() {};

    /** Adds (or replaces) the plugin's section. */
    default void register(Plugin plugin, Provider provider) { Profiles.register(plugin, provider); }

    /** See {@link Profiles#unregister}. */
    default void unregister(Plugin plugin) { Profiles.unregister(plugin); }

    /** Sections for a card: plugin name and its lines, sorted by plugin name; failing or empty providers are left out. */
    default List<Map.Entry<String, List<Component>>> sections(Player viewer, OfflinePlayer target) { return Profiles.sections(viewer, target); }

    /** Number of registered sections (for diagnostics and tests). */
    default int size() { return Profiles.size(); }
}
