package Vystorm.vystorm_core.chat;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Server-thread moderation hook before Core delivers command-based channel chat (currently /vtrade).
 * Cancelling prevents every delivery. This is not signed player chat and does not replace AsyncChatEvent.
 * @since 0.26.6
 */
public final class ChannelChatEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ChatChannels.Channel channel;
    private Component message;
    private boolean cancelled;
    public ChannelChatEvent(Player player, ChatChannels.Channel channel, Component message) {
        super(Objects.requireNonNull(player));
        this.channel = Objects.requireNonNull(channel);
        this.message = Objects.requireNonNull(message);
    }
    public ChatChannels.Channel channel() { return channel; }
    public Component message() { return message; }
    public void message(Component message) { this.message = Objects.requireNonNull(message); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
