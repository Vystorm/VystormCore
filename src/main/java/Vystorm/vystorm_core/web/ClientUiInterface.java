package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.ClientPlatform;
import Vystorm.vystorm_core.web.internal.ClientUiHub;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ClientUi}, reached through {@code api.web().clientUi()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientUi}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientUiInterface {
    /** The delegating default. Plugins use {@code api.web().clientUi()}. */
    ClientUiInterface DEFAULT = new ClientUiInterface() {};

    /** Access for a plugin (cached). Namespace = plugin name in lower case, characters other than {@code [a-z0-9_.-]} → {@code _}. */
    default ClientUi of(Plugin owner) { return ClientUi.of(owner); }

    /** Namespace from a plugin name (e.g. {@code VystormSkills} → {@code vystormskills}). */
    default String namespace(String pluginName) { return ClientUi.namespace(pluginName); }

    /** Plugin of a full element ID ({@code ns:name}), if it has registered. */
    default Optional<Plugin> ownerOf(String elementId) { return ClientUi.ownerOf(elementId); }

    /** Local ID from a full ID (without the namespace). */
    default String localId(String elementId) { return ClientUi.localId(elementId); }

    /** When a plugin is disabled, removes its elements for all players (through {@code CoreLifecycle}). */
    default void release(Plugin owner) { ClientUi.release(owner); }
}
