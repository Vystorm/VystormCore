package Vystorm.vystorm_core.chat;

import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Helpers for tagging ordinary chat messages with a client-visible channel. @since 0.26.6
 * <p>Preferred access: {@code api.message().channels()}.
 */
public final class ChatChannels {
    private static final String TRANSLATION_PREFIX = "vystorm.chat.channel.";

    private ChatChannels() {}

    public enum Channel {
        GLOBAL,
        PARTY,
        TRADE,
        SYSTEM;

        /** Stable lowercase key used in the client translation key. */
        public String wireKey() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Wraps the original rich component in a translatable component. The {@code %s} fallback renders the original
     * content for clients without the channel translations, retaining Adventure styles and events.
     * This pure transformation is safe to call from any thread.
     */
    public static Component tag(Channel channel, Component message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        return Component.translatable(TRANSLATION_PREFIX + channel.wireKey(), "%s", message);
    }

    /**
     * Sends a tagged message through the ordinary vanilla chat path. Must be called on the server thread.
     * The caller determines recipients and performs any authorization checks.
     */
    public static void send(Player player, Channel channel, Component message) {
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(Objects.requireNonNull(player, "player"), tag(channel, message));
    }
}
