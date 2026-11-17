package Vystorm.vystorm_core.news;

import Vystorm.vystorm_core.news.internal.NewsService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Server news board ({@code /vmenu news}): short posts that players see once as a clickable line when they join (or
 * right away if they are online) and can read again in the main menu. Admins write them in game; plugins may post too,
 * e.g. an event announcement or "the shop has new items".
 *
 * <pre>{@code
 * try {
 *     if (CoreApi.has(CoreApi.Capability.NEWS)) News.post(this, "Summer event", "From Friday: double XP at the arena.");
 * } catch (LinkageError olderCore) {}
 * }</pre>
 *
 * Posts are plain text (no colours), the title at most 64 and the text at most 1000 characters. When the server
 * switched the news off ({@code news.enabled: false}) nothing is posted. Server thread for {@link #open};
 * {@link #post} and {@link #posts} are thread-safe.
 *
 * <p>Preferred access: {@code api.message().news()}.
 *
 * @since 0.44.0
 */
public final class News {
    /** Read the news and get the join notice (everyone by default). */
    public static final String PERMISSION = "vystorm_core.news";
    /** Write and delete posts in game (op; included in {@code vystorm_core.admin}). */
    public static final String WRITE_PERMISSION = "vystorm_core.news.write";

    private News() {}

    /** One post; {@code author} is a player name or, for plugin posts, the plugin name. {@code time} in epoch millis. */
    public record Post(int id, long time, String author, String title, String text) {
        public Post {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(title, "title");
            text = text == null ? "" : text;
        }
    }

    /**
     * Publishes a post signed with the plugin's name and announces it to online players.
     *
     * @return the post, or empty if the news board is off or Core is not running
     * @throws IllegalArgumentException if {@code title} is blank
     */
    public static Optional<Post> post(Plugin plugin, String title, String text) {
        Objects.requireNonNull(plugin, "plugin");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is blank");
        return NewsService.postFromPlugin(plugin, title, text);
    }

    /** Current posts, newest first (empty if the board is off). */
    public static List<Post> posts() { return NewsService.currentPosts(); }

    /** How many posts the player has not opened yet. */
    public static int unread(Player player) { return NewsService.unreadCount(player); }

    /** Opens the news for the player (dialog); false if the board is off or the player lacks {@link #PERMISSION}. */
    public static boolean open(Player player) { return NewsService.openFor(player); }
}
