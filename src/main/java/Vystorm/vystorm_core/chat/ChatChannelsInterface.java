package Vystorm.vystorm_core.chat;

import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import Vystorm.vystorm_core.chat.ChatChannels.*;

/**
 * Manager view of {@link ChatChannels}, reached through {@code api.message().channels()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ChatChannels}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ChatChannelsInterface {
    /** The delegating default. Plugins use {@code api.message().channels()}. */
    ChatChannelsInterface DEFAULT = new ChatChannelsInterface() {};

    /**
     * Wraps the original rich component in a translatable component. The {@code %s} fallback renders the original
     * content for clients without the channel translations, retaining Adventure styles and events.
     * This pure transformation is safe to call from any thread.
     */
    default Component tag(Channel channel, Component message) { return ChatChannels.tag(channel, message); }

    /**
     * Sends a tagged message through the ordinary vanilla chat path. Must be called on the server thread.
     * The caller determines recipients and performs any authorization checks.
     */
    default void send(Player player, Channel channel, Component message) { ChatChannels.send(player, channel, message); }
}
