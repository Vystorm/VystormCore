package Vystorm.vystorm_core.network.bungee;

import Vystorm.vystorm_core.network.core.ChannelMessage;
import Vystorm.vystorm_core.network.core.HubRuntime;
import Vystorm.vystorm_core.network.core.NetworkLog;
import Vystorm.vystorm_core.network.core.ProxyBridge;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vystorm Core on BungeeCord/Waterfall: only the network hub. Declared in {@code bungee.yml} (BungeeCord prefers it
 * over {@code plugin.yml}). No shared secret exists here, so every server pairs once with a confirmation code.
 */
public final class BungeeEntry extends Plugin implements Listener {
    private HubRuntime runtime;

    @Override
    public void onEnable() {
        java.util.logging.Logger logger = getLogger();
        NetworkLog log = new NetworkLog() {
            @Override public void info(String message) { logger.info("[Network] " + message); }
            @Override public void warn(String message) { logger.warning("[Network] " + message); }
        };
        runtime = new HubRuntime(getDataFolder().toPath(), Optional.empty(), new Bridge(), log, Clock.systemUTC());
        getProxy().registerChannel(ChannelMessage.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new VnetCommand());
        runtime.start();
    }

    @Override
    public void onDisable() {
        if (runtime != null) runtime.stop();
    }

    /** Rule 1 of pairing: nothing a client sends on vystorm:net ever reaches a server. */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (ChannelMessage.CHANNEL.equals(event.getTag()) && event.getSender() instanceof ProxiedPlayer) event.setCancelled(true);
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        HubRuntime r = runtime;
        if (r == null) return;
        ProxiedPlayer player = event.getPlayer();
        boolean first = player.getServer() == null;
        r.playerReachedServer(player.getUniqueId(), player.getName(), event.getServer().getInfo().getName(), first,
                message -> event.getServer().sendData(ChannelMessage.CHANNEL, message));
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        HubRuntime r = runtime;
        if (r != null) r.playerLeft(event.getPlayer().getUniqueId());
    }

    private final class Bridge implements ProxyBridge {
        @Override public List<String> configuredServers() {
            return getProxy().getServers().keySet().stream().sorted().toList();
        }

        @Override public CompletableFuture<Boolean> sendPlayer(UUID playerId, String serverName) {
            ProxiedPlayer player = getProxy().getPlayer(playerId);
            ServerInfo server = getProxy().getServerInfo(serverName);
            if (player == null || server == null) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            player.connect(server, (ok, failure) -> result.complete(Boolean.TRUE.equals(ok) && failure == null));
            return result;
        }
    }

    /** Proxy console: {@code vnet status}, {@code vnet unpair <server>}. */
    private final class VnetCommand extends Command {
        VnetCommand() { super("vnet", "vystorm_core.network.admin"); }

        @Override public void execute(CommandSender sender, String[] args) {
            HubRuntime r = runtime;
            if (r == null) return;
            if (args.length == 2 && args[0].equalsIgnoreCase("unpair")) {
                sender.sendMessage(new TextComponent(r.unpair(args[1])
                        ? "Pairing of '" + args[1] + "' removed." : "No stored pairing for '" + args[1] + "'."));
                return;
            }
            for (String line : r.statusLines()) sender.sendMessage(new TextComponent(line));
        }
    }
}
