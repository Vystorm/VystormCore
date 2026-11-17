package Vystorm.vystorm_core.inbox;

import Vystorm.vystorm_core.inbox.internal.InboxService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * Personal inbox ({@code /vmenu inbox}): short messages for one player about things that happened while they were away
 * – a rent ran out, a payment failed, a contest was won. Online players get a clickable chat line at once, everyone
 * else when they join; the messages stay readable in the main menu until they expire.
 *
 * <pre>{@code
 * try {
 *     if (CoreApi.has(CoreApi.Capability.INBOX))
 *         Inbox.send(this, owner, "inbox.rent-expired", "plot", plot.name());
 * } catch (LinkageError olderCore) {}
 * }</pre>
 *
 * {@link #send} stores the plugin's namespace, the language key and the placeholders; the text is rendered with the
 * plugin's language bundle when the player reads it, so everyone sees it in their own language. Placeholder values
 * are stored as plain strings. All methods are thread-safe except {@link #open} (server thread).
 *
 * <p>Preferred access: {@code api.message().inbox()}.
 *
 * @since 0.55.0
 */
public final class Inbox {
    /** Read the inbox (everyone by default). */
    public static final String PERMISSION = "vystorm_core.inbox";

    private Inbox() {}

    /**
     * Leaves a message for {@code player}, rendered later from {@code key} in the plugin's language bundle.
     *
     * @param placeholders name/value pairs as for {@code Lang.text}
     * @return false if the inbox is switched off or Core is not running
     */
    public static boolean send(Plugin plugin, UUID player, String key, Object... placeholders) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is blank");
        return InboxService.sendKey(plugin, player, key, placeholders);
    }

    /** Leaves a plain text message (no colours, at most 500 characters); for texts that are not in a language file. */
    public static boolean sendText(Plugin plugin, UUID player, String text) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is blank");
        return InboxService.sendText(plugin, player, text);
    }

    /** Messages the player has not read yet. */
    public static int unread(Player player) { return InboxService.unreadCount(player); }

    /** Opens the inbox dialog; false if it is switched off or the player lacks {@link #PERMISSION}. */
    public static boolean open(Player player) { return InboxService.openFor(player); }
}
