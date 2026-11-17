package Vystorm.vystorm_core.network.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Proxy-side listener. Each participant gets one virtual thread; the first plain record decides between the normal
 * handshake (HELLO) and a pairing reply (PAIR_REPLY). A second connection with an already connected server name is
 * refused, the first one stays. Callbacks run on connection threads.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HubServer {
    /** Event sink of the hub; implemented by {@link HubNode}. */
    public interface Handler {
        void onConnect(String server);
        void onFrame(String server, Frame frame);
        void onDisconnect(String server);
        /** A PAIR_REPLY arrived; return true to accept it (spec: only for an open offer to that server). */
        default boolean onPairReply(String server, String offerId, byte[] publicKey) { return false; }
    }

    private final HubConfig config;
    private final Function<String, Optional<NetworkKey>> keyFor;
    private final Handler handler;
    private final NetworkLog log;
    private final ConnectionGuard guard;
    private final Map<String, SecureChannel> connections = new ConcurrentHashMap<>();
    private final Map<String, Socket> sockets = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile boolean running;

    public HubServer(HubConfig config, Function<String, Optional<NetworkKey>> keyFor, Handler handler, NetworkLog log) {
        this(config, keyFor, handler, log, Clock.systemUTC());
    }

    HubServer(HubConfig config, Function<String, Optional<NetworkKey>> keyFor, Handler handler, NetworkLog log, Clock clock) {
        this.config = config;
        this.keyFor = keyFor;
        this.handler = handler;
        this.log = log;
        this.guard = new ConnectionGuard(config.allowCidrs(), config.maxPending(), clock);
    }

    /** @return false (with one log line) if the port cannot be bound; the proxy keeps running without hub */
    public synchronized boolean start() {
        if (running) return true;
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(config.bindHost(), config.port()));
            server = socket;
        } catch (IOException failure) {
            log.warn("Hub could not listen on " + config.bindHost() + ":" + config.port() + " (" + failure.getMessage()
                    + "). Network features stay off until the port is free and the proxy restarts.");
            return false;
        }
        running = true;
        Thread.ofVirtual().name("vystorm-hub-accept").start(this::acceptLoop);
        log.info("Hub listening on " + config.bindHost() + ":" + port());
        return true;
    }

    /** Actual bound port (useful when configured as 0 in tests). */
    public int port() {
        ServerSocket s = server;
        return s == null ? config.port() : s.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets.values()) try { socket.close(); } catch (IOException ignored) { }
    }

    public Set<String> connected() { return Set.copyOf(connections.keySet()); }

    /** Sends to one server; a dead connection is closed and reported through {@link Handler#onDisconnect}. */
    public boolean send(String serverName, Frame frame) {
        SecureChannel channel = connections.get(serverName);
        if (channel == null) return false;
        try {
            channel.send(frame);
            return true;
        } catch (IOException failure) {
            close(serverName);
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket s = server;
            if (s == null) return;
            try {
                Socket socket = s.accept();
                InetAddress address = socket.getInetAddress();
                if (!guard.allow(address)) { socket.close(); continue; }
                Optional<AutoCloseable> slot = guard.pendingSlot();
                if (slot.isEmpty()) { socket.close(); continue; }
                Thread.ofVirtual().name("vystorm-hub-conn").start(() -> serve(socket, slot.get()));
            } catch (IOException failure) {
                if (running) log.warn("Hub accept failed: " + failure.getMessage());
            }
        }
    }

    private void serve(Socket socket, AutoCloseable slot) {
        InetAddress address = socket.getInetAddress();
        String name = null;
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) config.handshakeTimeout().toMillis());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            SecureChannel channel;
            try {
                Frame first = Handshake.readFirst(in);
                if (first.type() == FrameType.PAIR_REPLY) {
                    boolean accepted = handler.onPairReply(first.from(), first.channel(), first.payload());
                    Handshake.answerPairReply(out, accepted);
                    if (!accepted) guard.recordFailure(address);
                    return;
                }
                channel = Handshake.hub(first, this::keyForNewConnection, in, out);
            } catch (HandshakeException failure) {
                // A rejected name (unknown or already connected) reveals nothing about the key; only count real failures.
                if (failure.reason() != HandshakeException.Reason.NAME_REJECTED && guard.recordFailure(address)) {
                    log.warn("Blocked " + address.getHostAddress() + " for 10 minutes after repeated failed logins");
                }
                log.warn("Refused connection from " + address.getHostAddress() + ": " + failure.getMessage()
                        + (failure.reason() == HandshakeException.Reason.NAME_REJECTED ? " (unknown, unpaired or already connected)" : ""));
                return;
            } catch (IOException failure) {
                guard.recordFailure(address);
                return;
            } finally {
                closeQuietly(slot);
            }
            name = channel.peerName();
            if (connections.putIfAbsent(name, channel) != null) {
                log.warn("Server '" + name + "' is already connected; refused a second connection with the same name");
                name = null;
                return;
            }
            sockets.put(name, socket);
            socket.setSoTimeout((int) config.idleTimeout().toMillis());
            handler.onConnect(name);
            readLoop(name, channel);
        } catch (IOException ignored) {
            // socket closed
        } finally {
            if (name != null) {
                connections.remove(name);
                sockets.remove(name);
                handler.onDisconnect(name);
            }
        }
    }

    private Optional<NetworkKey> keyForNewConnection(String name) {
        if (connections.containsKey(name)) return Optional.empty();
        return keyFor.apply(name);
    }

    private void readLoop(String name, SecureChannel channel) throws IOException {
        while (running) {
            Frame frame;
            try {
                frame = channel.receive();
            } catch (SocketTimeoutException silent) {
                log.warn("Server '" + name + "' sent nothing for " + config.idleTimeout().toSeconds() + " s; disconnecting");
                return;
            } catch (SocketException closed) {
                return;
            }
            if (frame.type() == FrameType.HEARTBEAT) {
                channel.send(frame.withFrom(""));
                continue;
            }
            handler.onFrame(name, frame.withFrom(name));
        }
    }

    private void close(String serverName) {
        Socket socket = sockets.get(serverName);
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { }
    }
}
