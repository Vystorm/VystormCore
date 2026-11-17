package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Small per-player preferences stored in the player's persistent data (saved with the player file).
 * Use for plugins that have no own player storage; {@link #toggle} and friends create ready-made settings.
 * <p>Preferred access: {@code api.settings().prefs()}.
 */
public final class PlayerPrefs {
    private PlayerPrefs() {}

    private static NamespacedKey key(Plugin owner, String id) { return new NamespacedKey(owner, "pref." + id); }

    public static boolean bool(Plugin owner, Player player, String id, boolean fallback) {
        Byte v = player.getPersistentDataContainer().get(key(owner, id), PersistentDataType.BYTE);
        return v == null ? fallback : v != 0;
    }

    public static void setBool(Plugin owner, Player player, String id, boolean value) {
        player.getPersistentDataContainer().set(key(owner, id), PersistentDataType.BYTE, (byte) (value ? 1 : 0));
    }

    public static double number(Plugin owner, Player player, String id, double fallback) {
        Double v = player.getPersistentDataContainer().get(key(owner, id), PersistentDataType.DOUBLE);
        return v == null ? fallback : v;
    }

    public static void setNumber(Plugin owner, Player player, String id, double value) {
        player.getPersistentDataContainer().set(key(owner, id), PersistentDataType.DOUBLE, value);
    }

    public static String text(Plugin owner, Player player, String id, String fallback) {
        String v = player.getPersistentDataContainer().get(key(owner, id), PersistentDataType.STRING);
        return v == null ? fallback : v;
    }

    public static void setText(Plugin owner, Player player, String id, String value) {
        player.getPersistentDataContainer().set(key(owner, id), PersistentDataType.STRING, value);
    }

    /** Removes the player's own value so the fallback (e.g. the server default) applies again. */
    public static void clear(Plugin owner, Player player, String id) {
        player.getPersistentDataContainer().remove(key(owner, id));
    }

    public static boolean has(Plugin owner, Player player, String id) {
        return player.getPersistentDataContainer().has(key(owner, id));
    }

    /** A toggle stored in the player's persistent data. */
    public static Setting toggle(Plugin owner, String id, String label, String description, boolean fallback) {
        return Setting.toggle(id, label, description, p -> bool(owner, p, id, fallback),
                (p, v) -> { setBool(owner, p, id, v); return null; });
    }

    public static Setting number(Plugin owner, String id, String label, String description,
                                 double min, double max, double step, double fallback) {
        return Setting.number(id, label, description, min, max, step, p -> number(owner, p, id, fallback),
                (p, v) -> { setNumber(owner, p, id, v); return null; });
    }

    public static Setting choice(Plugin owner, String id, String label, String description,
                                 java.util.LinkedHashMap<String, String> options, String fallback) {
        return Setting.choice(id, label, description, options, p -> text(owner, p, id, fallback),
                (p, v) -> { setText(owner, p, id, v); return null; });
    }
}
