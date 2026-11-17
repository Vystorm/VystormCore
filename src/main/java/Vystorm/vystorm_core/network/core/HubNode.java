package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Hub logic on top of {@link HubServer}: keeps the authoritative {@link NetworkState}, forwards plugin messages
 * ({@code "*"} = every other server, {@code "@<uuid>"} = the player's current server, otherwise a server name;
 * unknown targets are dropped silently) and executes player moves through the {@link ProxyBridge}.
 * The proxy adapter reports logins, server switches and logouts.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HubNode implements HubServer.Handler {
    /** Pairing hook so the proxy adapter can decide about PAIR_REPLY frames. */
    public interface PairReplies { boolean accept(String server, String offerId, byte[] publicKey); }

    private final ProxyBridge bridge;
    private final NetworkLog log;
    private final NetworkState state = new NetworkState();
    private volatile HubServer server;
    private volatile PairReplies pairReplies = (s, o, k) -> false;

    public HubNode(ProxyBridge bridge, NetworkLog log) {
        this.bridge = bridge;
        this.log = log;
        for (String name : bridge.configuredServers()) state.serverKnown(name, false);
    }

    /** Must be called once before {@link HubServer#start()}. */
    public void attach(HubServer hubServer) { this.server = hubServer; }

    public void pairReplies(PairReplies handler) { this.pairReplies = handler; }

    public NetworkState state() { return state; }

    // ---- proxy events ----

    public void playerJoined(UUID player, String name, String serverName) { broadcast(state.playerJoined(player, name, serverName), null); }
    public void playerSwitched(UUID player, String serverName) { broadcast(state.playerSwitched(player, serverName), null); }
    public void playerLeft(UUID player) { broadcast(state.playerLeft(player), null); }

    // ---- HubServer.Handler ----

    @Override public void onConnect(String name) {
        Frame delta = state.serverKnown(name, true);
        send(name, state.snapshot());
        broadcast(delta, name);
    }

    @Override public void onDisconnect(String name) {
        broadcast(state.serverKnown(name, false), name);
    }

    @Override public boolean onPairReply(String serverName, String offerId, byte[] publicKey) {
        return pairReplies.accept(serverName, offerId, publicKey);
    }

    @Override public void onFrame(String from, Frame frame) {
        switch (frame.type()) {
            case MESSAGE -> route(from, frame);
            case SEND_PLAYER -> movePlayer(from, frame);
            default -> log.warn("Ignored unexpected " + frame.type() + " from '" + from + "'");
        }
    }

    private void route(String from, Frame frame) {
        String to = frame.to();
        if (Frame.ALL.equals(to)) {
            broadcast(frame, from);
        } else if (to.startsWith("@")) {
            try {
                state.serverOf(UUID.fromString(to.substring(1))).ifPresent(target -> send(target, frame));
            } catch (IllegalArgumentException badUuid) {
                // dropped: not a player target
            }
        } else if (!to.isEmpty()) {
            send(to, frame);
        }
    }

    private void movePlayer(String from, Frame frame) {
        long requestId;
        UUID player;
        String target;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()));
            requestId = in.readLong();
            player = NetworkState.readUuid(in);
            target = in.readUTF();
        } catch (IOException malformed) {
            return;
        }
        bridge.sendPlayer(player, target).exceptionally(failure -> false).thenAccept(ok ->
                send(from, Frame.of(FrameType.SEND_PLAYER_RESULT, NetworkState.write(out -> {
                    out.writeLong(requestId);
                    out.writeBoolean(Boolean.TRUE.equals(ok));
                }))));
    }

    private void send(String target, Frame frame) {
        HubServer s = server;
        if (s != null) s.send(target, frame);
    }

    private void broadcast(Frame frame, String except) {
        HubServer s = server;
        if (s == null) return;
        for (String name : s.connected()) if (!name.equals(except)) s.send(name, frame);
    }

    /** Connected server names (for proxy console output). */
    public Optional<HubServer> server() { return Optional.ofNullable(server); }
}
