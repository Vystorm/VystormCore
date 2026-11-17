package Vystorm.vystorm_core.network.core;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What the hub needs from the proxy platform (Velocity or BungeeCord).
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public interface ProxyBridge {
    /** Server names from the proxy configuration. */
    List<String> configuredServers();

    /** Moves an online player to a server; completes with false if the player or server is unknown or it failed. */
    CompletableFuture<Boolean> sendPlayer(UUID player, String server);
}
