package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link PlayerPrefs}, reached through {@code api.settings().prefs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link PlayerPrefs}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PlayerPrefsInterface {
    /** The delegating default. Plugins use {@code api.settings().prefs()}. */
    PlayerPrefsInterface DEFAULT = new PlayerPrefsInterface() {};

    /** See {@link PlayerPrefs#bool}. */
    default boolean bool(Plugin owner, Player player, String id, boolean fallback) { return PlayerPrefs.bool(owner, player, id, fallback); }

    /** See {@link PlayerPrefs#setBool}. */
    default void setBool(Plugin owner, Player player, String id, boolean value) { PlayerPrefs.setBool(owner, player, id, value); }

    /** See {@link PlayerPrefs#number}. */
    default double number(Plugin owner, Player player, String id, double fallback) { return PlayerPrefs.number(owner, player, id, fallback); }

    /** See {@link PlayerPrefs#setNumber}. */
    default void setNumber(Plugin owner, Player player, String id, double value) { PlayerPrefs.setNumber(owner, player, id, value); }

    /** See {@link PlayerPrefs#text}. */
    default String text(Plugin owner, Player player, String id, String fallback) { return PlayerPrefs.text(owner, player, id, fallback); }

    /** See {@link PlayerPrefs#setText}. */
    default void setText(Plugin owner, Player player, String id, String value) { PlayerPrefs.setText(owner, player, id, value); }

    /** Removes the player's own value so the fallback (e.g. the server default) applies again. */
    default void clear(Plugin owner, Player player, String id) { PlayerPrefs.clear(owner, player, id); }

    /** See {@link PlayerPrefs#has}. */
    default boolean has(Plugin owner, Player player, String id) { return PlayerPrefs.has(owner, player, id); }

    /** A toggle stored in the player's persistent data. */
    default Setting toggle(Plugin owner, String id, String label, String description, boolean fallback) {
        return PlayerPrefs.toggle(owner, id, label, description, fallback);
    }

    /** See {@link PlayerPrefs#number}. */
    default Setting number(Plugin owner, String id, String label, String description, double min, double max, double step, double fallback) {
        return PlayerPrefs.number(owner, id, label, description, min, max, step, fallback);
    }

    /** See {@link PlayerPrefs#choice}. */
    default Setting choice(Plugin owner, String id, String label, String description, java.util.LinkedHashMap<String, String> options, String fallback) {
        return PlayerPrefs.choice(owner, id, label, description, options, fallback);
    }
}
