package Vystorm.vystorm_core.compat;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;

/**
 * Manager view of {@link ServerCompat}, reached through {@code api.compat().server()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ServerCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ServerCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().server()}. */
    ServerCompatInterface DEFAULT = new ServerCompatInterface() {};

    /** Tests availability without initializing the optional class. Safe before plugin enable. */
    default boolean hasClass(String name) { return ServerCompat.hasClass(name); }

    /** Whether the server exposes the native dialog protocol and click events. */
    default boolean hasDialogs() { return ServerCompat.hasDialogs(); }

    /** Looks up renamed attribute constants across Paper API generations. */
    default org.bukkit.attribute.Attribute attribute(String modern, String legacy) { return ServerCompat.attribute(modern, legacy); }

    /** Resolves namespaced or Bukkit material names, returning fallback if absent. */
    default Material material(String key, Material fallback) { return ServerCompat.material(key, fallback); }

    /** Registry lookups require an initialized server; an unknown key returns fallback. */
    default EntityType entity(String key, EntityType fallback) { return ServerCompat.entity(key, fallback); }

    /** Registry lookups require an initialized server; an unknown key returns fallback. */
    default Sound sound(String key, Sound fallback) { return ServerCompat.sound(key, fallback); }
}
