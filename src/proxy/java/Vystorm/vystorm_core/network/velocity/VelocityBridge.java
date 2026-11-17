package Vystorm.vystorm_core.network.velocity;

import Vystorm.vystorm_core.network.core.ProxyBridge;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Velocity side of {@link ProxyBridge}: server list and player moves. */
final class VelocityBridge implements ProxyBridge {
    private final ProxyServer proxy;

    VelocityBridge(ProxyServer proxy) { this.proxy = proxy; }

    @Override public List<String> configuredServers() {
        return proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).sorted().toList();
    }

    @Override public CompletableFuture<Boolean> sendPlayer(UUID playerId, String serverName) {
        Optional<Player> player = proxy.getPlayer(playerId);
        Optional<RegisteredServer> server = proxy.getServer(serverName);
        if (player.isEmpty() || server.isEmpty()) return CompletableFuture.completedFuture(false);
        return player.get().createConnectionRequest(server.get()).connect()
                .thenApply(result -> result.isSuccessful())
                .exceptionally(failure -> false);
    }
}
