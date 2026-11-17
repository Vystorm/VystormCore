package Vystorm.vystorm_core.news;

import Vystorm.vystorm_core.news.internal.NewsService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import Vystorm.vystorm_core.news.News.*;

/**
 * Manager view of {@link News}, reached through {@code api.message().news()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link News}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface NewsInterface {
    /** The delegating default. Plugins use {@code api.message().news()}. */
    NewsInterface DEFAULT = new NewsInterface() {};

    /**
     * Publishes a post signed with the plugin's name and announces it to online players.
     *
     * @return the post, or empty if the news board is off or Core is not running
     * @throws IllegalArgumentException if {@code title} is blank
     */
    default Optional<Post> post(Plugin plugin, String title, String text) { return News.post(plugin, title, text); }

    /** Current posts, newest first (empty if the board is off). */
    default List<Post> posts() { return News.posts(); }

    /** How many posts the player has not opened yet. */
    default int unread(Player player) { return News.unread(player); }

    /** Opens the news for the player (dialog); false if the board is off or the player lacks {@link News#PERMISSION}. */
    default boolean open(Player player) { return News.open(player); }
}
