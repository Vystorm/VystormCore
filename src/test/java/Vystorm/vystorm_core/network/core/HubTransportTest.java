package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class HubTransportTest {
    private final NetworkKey key = KeySources.fromVelocitySecret("transport");
    private final List<Runnable> cleanup = new ArrayList<>();
    private final RecordingLog log = new RecordingLog();

    @AfterEach void stop() { cleanup.forEach(Runnable::run); }

    static final class RecordingLog implements NetworkLog {
        final List<String> warnings = new CopyOnWriteArrayList<>();
        @Override public void info(String message) {}
        @Override public void warn(String message) { warnings.add(message); }
    }

    static final class HubEvents implements HubServer.Handler {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        @Override public void onConnect(String server) { events.add("connect:" + server); }
        @Override public void onFrame(String server, Frame frame) { frames.add(frame); }
        @Override public void onDisconnect(String server) { events.add("disconnect:" + server); }
    }

    static final class ClientEvents implements HubClient.Handler {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        @Override public void onConnected() { events.add("connected"); }
        @Override public void onFrame(Frame frame) { frames.add(frame); }
        @Override public void onDisconnected() { events.add("disconnected"); }
    }

    private static HubConfig fast() {
        return new HubConfig("127.0.0.1", 0, List.of(), 64, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(600));
    }

    private HubServer hub(HubConfig config, HubEvents events) {
        HubServer hub = new HubServer(config, name -> Optional.of(key), events, log);
        assertTrue(hub.start());
        cleanup.add(hub::stop);
        return hub;
    }

    private HubClient client(HubServer hub, String name, NetworkKey clientKey, ClientEvents events, HubConfig timing) {
        HubClient client = new HubClient(() -> Optional.of(new InetSocketAddress("127.0.0.1", hub.port())), name,
                () -> Optional.of(clientKey), events, log, timing, new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), new Random(1)));
        client.start();
        cleanup.add(client::stop);
        return client;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not met within 5 s");
            Thread.sleep(20);
        }
    }

    @Test
    void givenHubAndClientWhenConnectedThenFramesFlowBothWays() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        ClientEvents clientEvents = new ClientEvents();
        HubClient client = client(hub, "lobby", key, clientEvents, fast());
        assertEquals("connect:lobby", hubEvents.events.poll(5, TimeUnit.SECONDS));
        assertEquals("connected", clientEvents.events.poll(5, TimeUnit.SECONDS));
        assertTrue(client.send(new Frame(FrameType.MESSAGE, "a:b", "spoofed", "*", new byte[]{1})));
        Frame atHub = hubEvents.frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(atHub);
        assertEquals("lobby", atHub.from(), "hub must stamp the authenticated sender");
        assertTrue(hub.send("lobby", Frame.of(FrameType.MESSAGE, new byte[]{2})));
        assertArrayEquals(new byte[]{2}, clientEvents.frames.poll(5, TimeUnit.SECONDS).payload());
    }

    @Test
    void givenIdleConnectionWhenHeartbeatsRunThenConnectionStaysAndLatencyKnown() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        HubClient client = client(hub, "lobby", key, new ClientEvents(), fast());
        await(client::connected);
        Thread.sleep(1500); // > idle timeout of 600 ms
        assertTrue(client.connected());
        assertTrue(hub.connected().contains("lobby"));
        assertTrue(client.latencyMillis() >= 0);
        assertFalse(hubEvents.events.contains("disconnect:lobby"));
    }

    @Test
    void givenSilentPeerWhenIdleTimeoutPassesThenHubDropsIt() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        // A client whose heartbeat interval is longer than the hub's idle timeout.
        HubConfig slowHeartbeat = new HubConfig("127.0.0.1", 0, List.of(), 64, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(30));
        client(hub, "quiet", key, new ClientEvents(), slowHeartbeat);
        assertEquals("connect:quiet", hubEvents.events.poll(5, TimeUnit.SECONDS));
        assertEquals("disconnect:quiet", hubEvents.events.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void givenSameNameTwiceThenSecondRefusedAndFirstStays() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        HubClient first = client(hub, "lobby", key, new ClientEvents(), fast());
        await(first::connected);
        HubClient second = client(hub, "lobby", key, new ClientEvents(), fast());
        Thread.sleep(800);
        assertTrue(first.connected());
        assertFalse(second.connected());
        assertEquals(1, hub.connected().size());
    }

    @Test
    void givenWrongKeyWhenConnectingThenRefusedAndLoggedWithoutKey() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        HubClient client = client(hub, "lobby", KeySources.fromVelocitySecret("wrong"), new ClientEvents(), fast());
        await(() -> log.warnings.stream().anyMatch(w -> w.contains("refused") || w.contains("Refused")));
        assertFalse(client.connected());
        String all = String.join("\n", log.warnings);
        assertFalse(all.contains(java.util.Base64.getEncoder().encodeToString(key.bytes())));
    }

    @Test
    void givenFiveFailedLoginsWithinAMinuteThenAddressBanned() {
        PairingTest.TestClock clock = new PairingTest.TestClock();
        ConnectionGuard guard = new ConnectionGuard(List.of(), 64, clock);
        InetAddress local = InetAddress.getLoopbackAddress();
        for (int i = 0; i < 4; i++) assertFalse(guard.recordFailure(local));
        assertTrue(guard.recordFailure(local));
        assertFalse(guard.allow(local));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertTrue(guard.allow(local));
    }

    @Test
    void givenFailuresSpreadOverMoreThanAMinuteThenNoBan() {
        PairingTest.TestClock clock = new PairingTest.TestClock();
        ConnectionGuard guard = new ConnectionGuard(List.of(), 64, clock);
        InetAddress local = InetAddress.getLoopbackAddress();
        for (int i = 0; i < 8; i++) { assertFalse(guard.recordFailure(local)); clock.advance(Duration.ofSeconds(20)); }
        assertTrue(guard.allow(local));
    }

    @Test
    void givenAllowListThenOnlyListedAddressesPass() throws Exception {
        ConnectionGuard guard = new ConnectionGuard(List.of("10.0.0.0/8", "192.168.1.5"), 64, java.time.Clock.systemUTC());
        assertTrue(guard.allow(InetAddress.getByName("10.20.30.40")));
        assertTrue(guard.allow(InetAddress.getByName("192.168.1.5")));
        assertFalse(guard.allow(InetAddress.getByName("192.168.1.6")));
        assertFalse(guard.allow(InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void givenPendingCapReachedThenNoMoreSlots() throws Exception {
        ConnectionGuard guard = new ConnectionGuard(List.of(), 2, java.time.Clock.systemUTC());
        AutoCloseable a = guard.pendingSlot().orElseThrow();
        guard.pendingSlot().orElseThrow();
        assertTrue(guard.pendingSlot().isEmpty());
        a.close();
        a.close();
        assertTrue(guard.pendingSlot().isPresent());
        assertTrue(guard.pendingSlot().isEmpty());
    }

    @Test
    void givenPortInUseWhenStartingThenFalseAndOneWarning() throws IOException {
        try (ServerSocket blocker = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            HubConfig taken = fast().withAddress("127.0.0.1", blocker.getLocalPort(), List.of());
            HubServer hub = new HubServer(taken, n -> Optional.of(key), new HubEvents(), log);
            cleanup.add(hub::stop);
            assertFalse(hub.start());
            assertEquals(1, log.warnings.size());
        }
    }

    @Test
    void givenHubDownWhenClientRetriesThenWarningsAreRateLimited() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) { freePort = probe.getLocalPort(); }
        int port = freePort;
        HubClient client = new HubClient(() -> Optional.of(new InetSocketAddress("127.0.0.1", port)), "lobby",
                () -> Optional.of(key), new ClientEvents(), log, fast(),
                new Backoff(Duration.ofMillis(10), Duration.ofMillis(20), new Random(1)));
        client.start();
        cleanup.add(client::stop);
        Thread.sleep(1500); // dozens of attempts
        client.stop();
        assertTrue(log.warnings.size() <= 5 + 4, "too many warnings: " + log.warnings.size());
        assertFalse(log.warnings.isEmpty());
    }

    @Test
    void givenBackoffThenDelaysDoubleAndCapAtThirtySeconds() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), new Random(7));
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 8; i++) seconds.add(backoff.next().toMillis());
        long[] base = {1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000};
        for (int i = 0; i < base.length; i++) {
            assertTrue(seconds.get(i) >= base[i] && seconds.get(i) <= base[i] * 1.2, i + ": " + seconds.get(i));
        }
        backoff.reset();
        assertTrue(backoff.next().toMillis() < 1300);
        assertEquals(Collections.emptyList(), Collections.emptyList());
    }

    @Test
    void givenOversizedRecordFromPeerThenHubClosesConnection() throws Exception {
        HubEvents hubEvents = new HubEvents();
        HubServer hub = hub(fast(), hubEvents);
        try (java.net.Socket raw = new java.net.Socket("127.0.0.1", hub.port())) {
            java.io.DataOutputStream out = new java.io.DataOutputStream(raw.getOutputStream());
            out.writeInt(Integer.MAX_VALUE);
            out.flush();
            raw.setSoTimeout(3000);
            assertEquals(-1, raw.getInputStream().read(), "hub must close the socket");
        }
    }
}
