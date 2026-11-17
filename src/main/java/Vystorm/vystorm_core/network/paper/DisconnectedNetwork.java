package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.network.Network;
import Vystorm.vystorm_core.network.NetworkListener;
import Vystorm.vystorm_core.network.NetworkServer;
import Vystorm.vystorm_core.network.Subscription;
import Vystorm.vystorm_core.network.core.ParticipantNode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link Network} when no proxy is configured or Core is stopped: every call is a harmless no-op.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class DisconnectedNetwork implements Network {
    public static final DisconnectedNetwork INSTANCE = new DisconnectedNetwork();

    private DisconnectedNetwork() {}

    @Override public boolean connected() { return false; }
    @Override public String serverName() { return ""; }
    @Override public Collection<NetworkServer> servers() { return List.of(); }
    @Override public Optional<String> serverOf(UUID player) { return Optional.empty(); }
    @Override public CompletableFuture<Boolean> sendPlayer(UUID player, String server) { return CompletableFuture.completedFuture(false); }
    @Override public void send(String server, String channel, byte[] payload) { checkChannel(channel); }
    @Override public void sendToPlayer(UUID player, String channel, byte[] payload) { checkChannel(channel); }
    @Override public void broadcast(String channel, byte[] payload) { checkChannel(channel); }
    @Override public Subscription subscribe(String channel, NetworkListener listener) {
        checkChannel(channel);
        return () -> { };
    }

    /** Same channel rules as when connected, so plugin bugs show up without a proxy too. */
    static void checkChannel(String channel) {
        if (channel == null || !ParticipantNode.CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("Channel must look like '<plugin>:<name>' (lowercase): " + channel);
        }
        if (channel.startsWith("vystorm:")) throw new IllegalArgumentException("Channels 'vystorm:*' are reserved for Vystorm Core");
    }
}
