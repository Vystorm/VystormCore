package Vystorm.vystorm_core.compat;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;

/**
 * Feature detection and key-based lookups for Paper 1.20.6 and later. @since 0.48.0
 * <p>Preferred access: {@code api.compat().server()}.
 */
public final class ServerCompat {
    private ServerCompat() {}

    /** Tests availability without initializing the optional class. Safe before plugin enable. */
    public static boolean hasClass(String name) {
        return hasClass(name, ServerCompat.class.getClassLoader());
    }

    static boolean hasClass(String name, ClassLoader loader) {
        try { Class.forName(name, false, loader); return true; }
        catch (ClassNotFoundException | LinkageError unavailable) { return false; }
    }

    /** Whether the server exposes the native dialog protocol and click events. */
    public static boolean hasDialogs() {
        return hasClass("io.papermc.paper.dialog.Dialog")
                && hasClass("io.papermc.paper.event.player.PlayerCustomClickEvent");
    }

    /** Looks up renamed attribute constants across Paper API generations. */
    public static org.bukkit.attribute.Attribute attribute(String modern, String legacy) {
        for (String name : new String[]{modern, legacy}) {
            try { return (org.bukkit.attribute.Attribute) org.bukkit.attribute.Attribute.class.getField(name).get(null); }
            catch (ReflectiveOperationException absent) { }
        }
        return null;
    }

    /** Resolves namespaced or Bukkit material names, returning fallback if absent. */
    public static Material material(String key, Material fallback) {
        Material found = key == null ? null : Material.matchMaterial(key);
        return found == null ? fallback : found;
    }

    /** Registry lookups require an initialized server; an unknown key returns fallback. */
    public static EntityType entity(String key, EntityType fallback) {
        NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        EntityType found = parsed == null ? null : Registry.ENTITY_TYPE.get(parsed);
        return found == null ? fallback : found;
    }

    /** Registry lookups require an initialized server; an unknown key returns fallback. */
    public static Sound sound(String key, Sound fallback) {
        NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        Sound found = parsed == null ? null : Registry.SOUNDS.get(parsed);
        return found == null ? fallback : found;
    }
}
