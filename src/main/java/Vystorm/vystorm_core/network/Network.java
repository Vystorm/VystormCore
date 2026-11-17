package Vystorm.vystorm_core.network;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-server network of Vystorm Core behind a Velocity or BungeeCord proxy (Core on the proxy acts as hub).
 * Get it with {@code CoreApi.network()}; it is never null. Without a proxy every method is a safe no-op:
 * {@link #connected()} is false, sends are dropped, {@link #servers()} is empty and {@link #sendPlayer} completes
 * with false. Check {@code CoreApi.has(CoreApi.Capability.NETWORK)} before using it on older Core versions.
 *
 * <p>Channels are {@code "<plugin>:<name>"} in lowercase; {@code vystorm:*} is reserved for Core. Payloads are opaque
 * bytes up to about 1 MiB. Listeners run on the server main thread. All methods are thread-safe.
 *
 * @since 0.53.0
 */
public interface Network {
    /** True while this server is connected to the hub. */
    boolean connected();

    /** This server's name in the proxy configuration, or {@code ""} when unknown. */
    String serverName();

    /** Every server the proxy knows, sorted by name (empty when not connected). */
    Collection<NetworkServer> servers();

    /** The server a player is on anywhere in the network. */
    Optional<String> serverOf(UUID player);

    /** Asks the proxy to move an online player; completes with false if that was not possible within 10 s. */
    CompletableFuture<Boolean> sendPlayer(UUID player, String server);

    /** Sends to one server by name; dropped silently when not connected or the server is unknown. */
    void send(String server, String channel, byte[] payload);

    /** Sends to the server the given player is currently on (follows server switches). */
    void sendToPlayer(UUID player, String channel, byte[] payload);

    /** Sends to every other connected server. */
    void broadcast(String channel, byte[] payload);

    /** Receives messages on a channel until the subscription is closed (closed automatically when Core stops). */
    Subscription subscribe(String channel, NetworkListener listener);
}
