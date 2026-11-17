package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Participant logic on top of {@link HubClient}: mirrors the network state, delivers plugin messages to channel
 * subscribers on the given executor (the Paper main thread) and asks the hub to move players.
 * Channels {@code vystorm:*} are reserved for Core; {@link #send} and {@link #broadcast} reject them.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ParticipantNode implements HubClient.Handler {
    public static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");
    private static final long MOVE_TIMEOUT_SECONDS = 10;

    private final Executor mainThread;
    private final NetworkLog log;
    private final NetworkState state = new NetworkState();
    private final Map<String, List<BiConsumer<String, byte[]>>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Boolean>> moves = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final List<Runnable> connectionListeners = new CopyOnWriteArrayList<>();
    private volatile HubClient client;

    public ParticipantNode(Executor mainThread, NetworkLog log) {
        this.mainThread = mainThread;
        this.log = log;
    }

    public void attach(HubClient hubClient) { this.client = hubClient; }

    public boolean connected() { HubClient c = client; return c != null && c.connected(); }

    public String serverName() { HubClient c = client; return c == null ? "" : c.serverName(); }

    public long latencyMillis() { HubClient c = client; return c == null ? -1 : c.latencyMillis(); }

    public NetworkState state() { return state; }

    /** Runs on the main thread whenever the hub connection comes up or goes down. */
    public void onConnectionChange(Runnable listener) { connectionListeners.add(listener); }

    public AutoCloseable subscribe(String channel, BiConsumer<String, byte[]> listener) {
        requireChannel(channel, true);
        subscribers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<BiConsumer<String, byte[]>> list = subscribers.get(channel);
            if (list != null) list.remove(listener);
        };
    }

    public boolean send(String server, String channel, byte[] payload) {
        requireChannel(channel, false);
        return sendRaw(server, channel, payload);
    }

    public boolean broadcast(String channel, byte[] payload) {
        requireChannel(channel, false);
        return sendRaw(Frame.ALL, channel, payload);
    }

    /** Core-internal sends may use {@code vystorm:*} channels. */
    public boolean sendInternal(String to, String channel, byte[] payload) {
        requireChannel(channel, true);
        return sendRaw(to, channel, payload);
    }

    public CompletableFuture<Boolean> sendPlayer(UUID player, String server) {
        HubClient c = client;
        if (c == null || !c.connected()) return CompletableFuture.completedFuture(false);
        long id = requestIds.incrementAndGet();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        moves.put(id, result);
        boolean sent = c.send(Frame.of(FrameType.SEND_PLAYER, NetworkState.write(out -> {
            out.writeLong(id);
            NetworkState.writeUuid(out, player);
            out.writeUTF(server);
        })));
        if (!sent) {
            moves.remove(id);
            return CompletableFuture.completedFuture(false);
        }
        return result.completeOnTimeout(false, MOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ok, failure) -> moves.remove(id));
    }

    private boolean sendRaw(String to, String channel, byte[] payload) {
        HubClient c = client;
        return c != null && c.send(new Frame(FrameType.MESSAGE, channel, "", to, payload == null ? new byte[0] : payload));
    }

    private static void requireChannel(String channel, boolean allowReserved) {
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("Channel must look like '<plugin>:<name>' (lowercase): " + channel);
        }
        if (!allowReserved && channel.startsWith("vystorm:")) {
            throw new IllegalArgumentException("Channels 'vystorm:*' are reserved for Vystorm Core");
        }
    }

    // ---- HubClient.Handler (connection threads) ----

    @Override public void onConnected() { fireConnectionChange(); }

    @Override public void onDisconnected() {
        state.clear();
        for (CompletableFuture<Boolean> move : moves.values()) move.complete(false);
        moves.clear();
        fireConnectionChange();
    }

    @Override public void onFrame(Frame frame) {
        switch (frame.type()) {
            case STATE_SNAPSHOT, STATE_DELTA -> {
                try { state.apply(frame); }
                catch (FrameException malformed) { log.warn("Ignored malformed state update from hub"); }
            }
            case MESSAGE -> deliver(frame);
            case SEND_PLAYER_RESULT -> {
                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()));
                    CompletableFuture<Boolean> move = moves.remove(in.readLong());
                    if (move != null) move.complete(in.readBoolean());
                } catch (IOException malformed) {
                    // ignore
                }
            }
            default -> { }
        }
    }

    private void deliver(Frame frame) {
        List<BiConsumer<String, byte[]>> list = subscribers.get(frame.channel());
        if (list == null || list.isEmpty()) return;
        String from = frame.from();
        byte[] payload = frame.payload();
        mainThread.execute(() -> {
            for (BiConsumer<String, byte[]> listener : list) {
                try {
                    listener.accept(from, payload.clone());
                } catch (RuntimeException failure) {
                    log.warn("Network listener for '" + frame.channel() + "' failed: " + failure);
                }
            }
        });
    }

    private void fireConnectionChange() {
        mainThread.execute(() -> {
            for (Runnable listener : connectionListeners) {
                try { listener.run(); } catch (RuntimeException failure) { log.warn("Connection listener failed: " + failure); }
            }
        });
    }
}
