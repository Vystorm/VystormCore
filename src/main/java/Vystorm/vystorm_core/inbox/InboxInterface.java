package Vystorm.vystorm_core.inbox;

import Vystorm.vystorm_core.inbox.internal.InboxService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.Objects;
import java.util.UUID;

/**
 * Manager view of {@link Inbox}, reached through {@code api.message().inbox()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link Inbox}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface InboxInterface {
    /** The delegating default. Plugins use {@code api.message().inbox()}. */
    InboxInterface DEFAULT = new InboxInterface() {};

    /**
     * Leaves a message for {@code player}, rendered later from {@code key} in the plugin's language bundle.
     *
     * @param placeholders name/value pairs as for {@code Lang.text}
     * @return false if the inbox is switched off or Core is not running
     */
    default boolean send(Plugin plugin, UUID player, String key, Object... placeholders) { return Inbox.send(plugin, player, key, placeholders); }

    /** Leaves a plain text message (no colours, at most 500 characters); for texts that are not in a language file. */
    default boolean sendText(Plugin plugin, UUID player, String text) { return Inbox.sendText(plugin, player, text); }

    /** Messages the player has not read yet. */
    default int unread(Player player) { return Inbox.unread(player); }

    /** Opens the inbox dialog; false if it is switched off or the player lacks {@link Inbox#PERMISSION}. */
    default boolean open(Player player) { return Inbox.open(player); }
}
