package Vystorm.vystorm_core.network.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Participant-side connection to the hub with automatic reconnect (Backoff) and heartbeat-based latency.
 * While the hub is unreachable the server simply runs without network features; warnings are rate-limited
 * (first failure, then whenever the delay grows, then every 20th attempt ≈ 10 minutes).
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HubClient {
    /** Event sink of the participant; implemented by {@link ParticipantNode}. */
    public interface Handler {
        void onConnected();
        void onFrame(Frame frame);
        void onDisconnected();
    }

    private final Supplier<Optional<InetSocketAddress>> hub;
    private final String serverName;
    private final Supplier<Optional<NetworkKey>> key;
    private final Handler handler;
    private final NetworkLog log;
    private final HubConfig timing;
    private final Backoff backoff;
    private final AtomicLong latencyMillis = new AtomicLong(-1);
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile SecureChannel channel;
    private volatile Socket socket;
    private Thread loop;
    private Thread heartbeat;

    public HubClient(Supplier<Optional<InetSocketAddress>> hub, String serverName, Supplier<Optional<NetworkKey>> key,
                     Handler handler, NetworkLog log) {
        this(hub, serverName, key, handler, log, HubConfig.defaults(), new Backoff());
    }

    HubClient(Supplier<Optional<InetSocketAddress>> hub, String serverName, Supplier<Optional<NetworkKey>> key,
              Handler handler, NetworkLog log, HubConfig timing, Backoff backoff) {
        this.hub = hub;
        this.serverName = serverName;
        this.key = key;
        this.handler = handler;
        this.log = log;
        this.timing = timing;
        this.backoff = backoff;
    }

    public String serverName() { return serverName; }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            loop = Thread.ofVirtual().name("vystorm-net-client").start(this::run);
            heartbeat = Thread.ofVirtual().name("vystorm-net-heartbeat").start(this::heartbeats);
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            closeSocket();
            if (loop != null) loop.interrupt();
            if (heartbeat != null) heartbeat.interrupt();
        }
    }

    /** Wakes the reconnect loop now (e.g. right after a pairing was confirmed). */
    public void reconnectNow() {
        backoff.reset();
        Thread t = loop;
        if (t != null) t.interrupt();
    }

    public boolean connected() { return channel != null; }

    /** Last round trip to the hub in milliseconds, or -1 if unknown. */
    public long latencyMillis() { return latencyMillis.get(); }

    public boolean send(Frame frame) {
        SecureChannel c = channel;
        if (c == null) return false;
        try {
            c.send(frame.withFrom(serverName));
            return true;
        } catch (IOException failure) {
            closeSocket();
            return false;
        }
    }

    private void run() {
        while (running) {
            Optional<NetworkKey> currentKey = key.get();
            Optional<InetSocketAddress> address = hub.get();
            if (currentKey.isPresent() && address.isPresent()) {
                try {
                    connectAndRead(address.get(), currentKey.get());
                    backoff.reset();
                } catch (HandshakeException refused) {
                    logFailure("Hub refused this server: " + refused.getMessage());
                } catch (IOException failure) {
                    logFailure("Hub " + address.get().getHostString() + ":" + address.get().getPort()
                            + " not reachable (" + failure.getMessage() + ")");
                }
            }
            if (!running) return;
            try {
                Thread.sleep(backoff.next());
            } catch (InterruptedException wake) {
                // stop() or reconnectNow()
            }
        }
    }

    private void connectAndRead(InetSocketAddress address, NetworkKey networkKey) throws IOException {
        Socket s = new Socket();
        socket = s;
        try (s) {
            s.setTcpNoDelay(true);
            s.connect(address, (int) timing.handshakeTimeout().toMillis());
            s.setSoTimeout((int) timing.handshakeTimeout().toMillis());
            SecureChannel c = Handshake.client(networkKey, serverName,
                    new BufferedInputStream(s.getInputStream()), new BufferedOutputStream(s.getOutputStream()));
            s.setSoTimeout((int) timing.idleTimeout().toMillis());
            channel = c;
            log.info("Connected to hub " + address.getHostString() + ":" + address.getPort() + " as '" + serverName
                    + "' (key " + networkKey.fingerprint() + ")");
            backoff.reset();
            handler.onConnected();
            while (running) {
                Frame frame;
                try {
                    frame = c.receive();
                } catch (SocketTimeoutException silent) {
                    log.warn("Hub sent nothing for " + timing.idleTimeout().toSeconds() + " s; reconnecting");
                    return;
                }
                if (frame.type() == FrameType.HEARTBEAT) {
                    if (frame.payload().length == Long.BYTES) {
                        long sent = ByteBuffer.wrap(frame.payload()).getLong();
                        latencyMillis.set(Math.max(0, Duration.ofNanos(System.nanoTime() - sent).toMillis()));
                    }
                    continue;
                }
                handler.onFrame(frame);
            }
        } finally {
            boolean wasConnected = channel != null;
            channel = null;
            socket = null;
            if (wasConnected) {
                log.warn("Lost connection to hub; reconnecting");
                handler.onDisconnected();
            }
        }
    }

    private void heartbeats() {
        while (running) {
            try {
                Thread.sleep(timing.heartbeat());
            } catch (InterruptedException stop) {
                if (!running) return;
            }
            send(Frame.of(FrameType.HEARTBEAT, ByteBuffer.allocate(Long.BYTES).putLong(System.nanoTime()).array()));
        }
    }

    private void logFailure(String message) {
        int attempt = backoff.attempt();
        boolean growing = attempt <= 5;
        if (growing || attempt % 20 == 0) log.warn(message + "; retrying");
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
