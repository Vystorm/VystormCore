package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.List;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * A connection between Core's placeholders and another placeholder plugin, in both directions:
 * <ul>
 *   <li>{@link #provide}: the other plugin learns to ask Core, so {@code %vystorm_...%} works in every plugin that
 *       uses it;</li>
 *   <li>{@link #consume}: Core asks the other plugin for a placeholder it does not answer itself, so foreign
 *       placeholders work inside Vystorm texts.</li>
 * </ul>
 * Core ships the bridge to PlaceholderAPI. A plugin adds another one with
 * {@link PlaceholderInterface#bridge(org.bukkit.plugin.Plugin, PlaceholderBridge)}; it is removed when that plugin
 * is disabled.
 * <p>
 * A bridge must not need the other plugin to load: keep every class of the other plugin behind reflection or in a
 * class of its own that is only loaded once {@link #provide} found the plugin enabled.
 * <p>
 * {@link #provide} and {@link #withdraw} are called on the server thread. {@link #consume} is called on the server
 * thread, and from other threads only if {@link #asyncSafe()} says so.
 *
 * @since 0.65.0
 */
public interface PlaceholderBridge {
    /** What the other plugin calls to get a Vystorm value. */
    interface Source {
        /**
         * Answers {@code <namespace>_<key>[_<arguments>]} (the part after {@code vystorm_}).
         *
         * @param viewer the player the text is for, or {@code null}
         * @param other the second player of a relational placeholder, or {@code null}
         * @return the value, or {@code null} if nothing answers; values of namespaces that are not async-safe are
         *         {@code null} off the server thread
         */
        String request(OfflinePlayer viewer, Player other, String params);

        /** Every declared placeholder as {@code <namespace>_<key>}, for a plugin that must know them in advance. */
        default List<String> known() { return List.of(); }
    }

    /** The other plugin's name as admins know it: {@code PlaceholderAPI}. */
    String name();

    /** Whether the other plugin is installed and enabled right now. */
    boolean available();

    /**
     * Connects the other plugin to {@code source} if it is available and not connected yet, and disconnects when it
     * is gone. Core calls this when the bridge is added, whenever a plugin is enabled or disabled, and from
     * {@code /vadmin placeholders}; it must be cheap and safe to call again.
     *
     * @return whether the other plugin now asks Core
     */
    boolean provide(Source source);

    /** Whether {@link #provide} is in effect: the other plugin asks Core for {@code %vystorm_...%}. */
    boolean providing();

    /**
     * Asks the other plugin for one placeholder exactly as written in the text, for example {@code %player_name%}.
     *
     * @return its value, or {@code null} if the other plugin does not know it or is not available
     */
    String consume(OfflinePlayer viewer, Player other, String placeholder);

    /** Disconnects from the other plugin. Called when the bridge is removed and when Core is disabled. */
    default void withdraw() {}

    /** Whether {@link #consume} may be called off the server thread. */
    default boolean asyncSafe() { return false; }
}
