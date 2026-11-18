package Vystorm.vystorm_core.chat;

import Vystorm.vystorm_core.lang.Localization;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Command-based trade channel. Only server-thread calls; no persisted messages or channel membership.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class TradeChatCommand implements TabExecutor, Listener {
    public static final String SEND_PERMISSION = "vystorm_core.chat.trade.send";
    public static final String RECEIVE_PERMISSION = "vystorm_core.chat.trade.receive";
    private final Map<UUID, Long> sent = new HashMap<>();
    private final LongSupplier nanos;
    public TradeChatCommand() { this(System::nanoTime); }
    TradeChatCommand(LongSupplier nanos) { this.nanos = nanos; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var lang = Localization.get().core();
        // trade-chat.enabled off (0.65.0): the channel is closed
        if (!Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.TRADE_CHAT)) { lang.send(sender, "feature-off"); return true; }
        if (!(sender instanceof Player player)) { lang.send(sender, "channel-chat.players-only"); return true; }
        if (!player.hasPermission(SEND_PERMISSION)) { feedback(player, "chat.no-permission"); return true; }
        String text = String.join(" ", args).trim();
        if (text.isEmpty()) { feedback(player, "channel-chat.usage"); return true; }
        if (text.length() > 240 || text.codePoints().anyMatch(Character::isISOControl)) {
            feedback(player, "channel-chat.too-long"); return true;
        }
        UUID id = player.getUniqueId(); long now = nanos.getAsLong();
        Long previous = sent.get(id);
        if (previous != null && now - previous < 2_000_000_000L) {
            feedback(player, "channel-chat.wait"); return true;
        }
        sent.put(id, now);
        var event = new ChannelChatEvent(player, ChatChannels.Channel.TRADE, Component.text(text));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !player.isOnline() || !player.hasPermission(SEND_PERMISSION)) return true;
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!recipient.hasPermission(RECEIVE_PERMISSION) && !recipient.getUniqueId().equals(id)) continue;
            Component line = lang.component(recipient, "channel-chat.trade-prefix").color(NamedTextColor.GOLD)
                    .append(Component.text(player.getName() + ": ", NamedTextColor.WHITE)).append(event.message());
            ChatChannels.send(recipient, ChatChannels.Channel.TRADE, line);
        }
        return true;
    }
    private static void feedback(Player player, String key) {
        ChatChannels.send(player, ChatChannels.Channel.TRADE, Localization.get().core().component(player, key));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { sent.remove(event.getPlayer().getUniqueId()); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
