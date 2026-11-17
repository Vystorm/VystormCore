package Vystorm.vystorm_core.network.velocity;

import Vystorm.vystorm_core.network.core.ChannelMessage;
import Vystorm.vystorm_core.network.core.HubRuntime;
import Vystorm.vystorm_core.network.core.KeySources;
import Vystorm.vystorm_core.network.core.NetworkLog;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Vystorm Core on Velocity: only the network hub (no menus, worlds or database). Declared in
 * {@code velocity-plugin.json}; Velocity injects the constructor arguments.
 */
public final class VelocityEntry {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(ChannelMessage.CHANNEL);

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final NetworkLog log;
    private HubRuntime runtime;

    @Inject
    public VelocityEntry(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.log = new NetworkLog() {
            @Override public void info(String message) { logger.info("[Network] {}", message); }
            @Override public void warn(String message) { logger.warn("[Network] {}", message); }
        };
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        Path proxyRoot = dataDirectory.toAbsolutePath().getParent().getParent();
        Path toml = proxyRoot.resolve("velocity.toml");
        Optional<String> secret = KeySources.velocityModernForwarding(toml)
                ? KeySources.readVelocityForwardingSecret(toml, proxyRoot) : Optional.empty();
        runtime = new HubRuntime(dataDirectory, secret, new VelocityBridge(proxy), log, Clock.systemUTC());
        proxy.getChannelRegistrar().register(CHANNEL);
        runtime.start();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vnet").plugin(this).build(), new VnetCommand());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (runtime != null) runtime.stop();
    }

    /** Rule 1 of pairing: nothing a client sends on vystorm:net ever reaches a server. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(ChannelMessage.CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        HubRuntime r = runtime;
        if (r == null) return;
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(connection -> r.playerReachedServer(player.getUniqueId(), player.getUsername(),
                connection.getServerInfo().getName(), event.getPreviousServer() == null,
                message -> connection.sendPluginMessage(CHANNEL, message)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        HubRuntime r = runtime;
        if (r != null) r.playerLeft(event.getPlayer().getUniqueId());
    }

    /** Proxy console: {@code vnet status}, {@code vnet unpair <server>}. Console or {@code vystorm_core.network.admin}. */
    private final class VnetCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            HubRuntime r = runtime;
            if (r == null) return;
            String[] args = invocation.arguments();
            if (args.length == 2 && args[0].equalsIgnoreCase("unpair")) {
                invocation.source().sendMessage(Component.text(r.unpair(args[1])
                        ? "Pairing of '" + args[1] + "' removed." : "No stored pairing for '" + args[1] + "'."));
                return;
            }
            List<String> lines = r.statusLines();
            for (String line : lines) invocation.source().sendMessage(Component.text(line));
        }

        @Override public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("vystorm_core.network.admin");
        }
    }
}
