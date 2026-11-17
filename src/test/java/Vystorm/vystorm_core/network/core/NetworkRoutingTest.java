package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Hub + participants over real loopback sockets. */
class NetworkRoutingTest {
    private final NetworkKey key = KeySources.fromVelocitySecret("routing");
    private final List<Runnable> cleanup = new ArrayList<>();
    private final List<UUID[]> moves = new ArrayList<>();
    private HubNode hubNode;
    private HubServer hubServer;

    @AfterEach void stop() { cleanup.forEach(Runnable::run); }

    private static HubConfig fast() {
        return new HubConfig("127.0.0.1", 0, List.of(), 64, Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(3));
    }

    private void startHub() {
        ProxyBridge bridge = new ProxyBridge() {
            @Override public List<String> configuredServers() { return List.of("lobby", "survival", "creative"); }
            @Override public CompletableFuture<Boolean> sendPlayer(UUID player, String server) {
                return CompletableFuture.completedFuture(server.equals("survival"));
            }
        };
        hubNode = new HubNode(bridge, NetworkLog.SILENT);
        hubServer = new HubServer(fast(), name -> Optional.of(key), hubNode, NetworkLog.SILENT);
        hubNode.attach(hubServer);
        assertTrue(hubServer.start());
        cleanup.add(hubServer::stop);
    }

    private ParticipantNode participant(String name, Executor executor) {
        ParticipantNode node = new ParticipantNode(executor, NetworkLog.SILENT);
        HubClient client = new HubClient(() -> Optional.of(new InetSocketAddress("127.0.0.1", hubServer.port())), name,
                () -> Optional.of(key), node, NetworkLog.SILENT, fast(),
                new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), new Random(3)));
        node.attach(client);
        client.start();
        cleanup.add(client::stop);
        return node;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not met within 5 s");
            Thread.sleep(20);
        }
    }

    @Test
    void givenBroadcastThenEveryOtherServerReceivesItButNotTheSender() throws Exception {
        startHub();
        Executor direct = Runnable::run;
        ParticipantNode lobby = participant("lobby", direct);
        ParticipantNode survival = participant("survival", direct);
        ParticipantNode creative = participant("creative", direct);
        await(() -> lobby.connected() && survival.connected() && creative.connected());
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        for (ParticipantNode n : List.of(lobby, survival, creative)) {
            n.subscribe("test:chat", (from, data) -> got.add(n.serverName() + "<-" + from + ":" + new String(data)));
        }
        assertTrue(lobby.broadcast("test:chat", "hi".getBytes()));
        List<String> received = new ArrayList<>();
        for (int i = 0; i < 2; i++) received.add(got.poll(5, TimeUnit.SECONDS));
        assertTrue(received.contains("survival<-lobby:hi"));
        assertTrue(received.contains("creative<-lobby:hi"));
        assertNull(got.poll(300, TimeUnit.MILLISECONDS), "sender must not receive its own broadcast");
    }

    @Test
    void givenPlayerTargetThenMessageFollowsServerSwitch() throws Exception {
        startHub();
        ParticipantNode lobby = participant("lobby", Runnable::run);
        ParticipantNode survival = participant("survival", Runnable::run);
        ParticipantNode creative = participant("creative", Runnable::run);
        await(() -> lobby.connected() && survival.connected() && creative.connected());
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        survival.subscribe("test:mail", (from, data) -> got.add("survival"));
        creative.subscribe("test:mail", (from, data) -> got.add("creative"));
        UUID player = UUID.randomUUID();
        hubNode.playerJoined(player, "Esoren", "survival");
        lobby.send(Frame.playerTarget(player), "test:mail", new byte[0]);
        assertEquals("survival", got.poll(5, TimeUnit.SECONDS));
        hubNode.playerSwitched(player, "creative");
        lobby.send(Frame.playerTarget(player), "test:mail", new byte[0]);
        assertEquals("creative", got.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void givenUnknownPlayerTargetThenDroppedSilently() throws Exception {
        startHub();
        ParticipantNode lobby = participant("lobby", Runnable::run);
        ParticipantNode survival = participant("survival", Runnable::run);
        await(() -> lobby.connected() && survival.connected());
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        survival.subscribe("test:mail", (from, data) -> got.add("x"));
        assertDoesNotThrow(() -> lobby.send(Frame.playerTarget(UUID.randomUUID()), "test:mail", new byte[0]));
        assertDoesNotThrow(() -> lobby.send("@not-a-uuid", "test:mail", new byte[0]));
        assertNull(got.poll(300, TimeUnit.MILLISECONDS));
        assertTrue(lobby.connected(), "a bad target must not break the connection");
    }

    @Test
    void givenConnectedParticipantThenStateMirrorsHubIncludingDeltas() throws Exception {
        startHub();
        UUID early = UUID.randomUUID();
        hubNode.playerJoined(early, "Early", "lobby");
        ParticipantNode lobby = participant("lobby", Runnable::run);
        await(lobby::connected);
        await(() -> lobby.state().serverOf(early).isPresent());
        UUID late = UUID.randomUUID();
        hubNode.playerJoined(late, "Late", "creative");
        await(() -> lobby.state().serverOf(late).equals(Optional.of("creative")));
        hubNode.playerLeft(early);
        await(() -> lobby.state().serverOf(early).isEmpty());
        assertEquals(Optional.of(late), lobby.state().playerByName("late"));
        NetworkServerInfo lobbyInfo = lobby.state().servers().stream().filter(s -> s.name().equals("lobby")).findFirst().orElseThrow();
        assertTrue(lobbyInfo.connected());
        NetworkServerInfo creativeInfo = lobby.state().servers().stream().filter(s -> s.name().equals("creative")).findFirst().orElseThrow();
        assertEquals(1, creativeInfo.players());
        assertFalse(creativeInfo.connected());
    }

    @Test
    void givenReservedChannelFromPluginThenRejected() {
        ParticipantNode node = new ParticipantNode(Runnable::run, NetworkLog.SILENT);
        assertThrows(IllegalArgumentException.class, () -> node.send("lobby", "vystorm:broadcast", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> node.broadcast("vystorm:x", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> node.send("lobby", "No Colon", new byte[0]));
        assertFalse(node.send("lobby", "myplugin:data", new byte[0]), "not connected yet");
    }

    @Test
    void givenListenerThenItRunsOnProvidedExecutor() throws Exception {
        startHub();
        java.util.concurrent.ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-main"));
        cleanup.add(main::shutdownNow);
        ParticipantNode lobby = participant("lobby", Runnable::run);
        ParticipantNode survival = participant("survival", main);
        await(() -> lobby.connected() && survival.connected());
        BlockingQueue<String> thread = new LinkedBlockingQueue<>();
        survival.subscribe("test:t", (from, data) -> thread.add(Thread.currentThread().getName()));
        lobby.send("survival", "test:t", new byte[0]);
        assertEquals("fake-main", thread.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void givenSendPlayerThenHubResultReturnedToCaller() throws Exception {
        startHub();
        ParticipantNode lobby = participant("lobby", Runnable::run);
        await(lobby::connected);
        assertTrue(lobby.sendPlayer(UUID.randomUUID(), "survival").get(5, TimeUnit.SECONDS));
        assertFalse(lobby.sendPlayer(UUID.randomUUID(), "creative").get(5, TimeUnit.SECONDS));
    }

    @Test
    void givenNotConnectedThenSendPlayerCompletesFalse() throws Exception {
        ParticipantNode node = new ParticipantNode(Runnable::run, NetworkLog.SILENT);
        assertFalse(node.sendPlayer(UUID.randomUUID(), "x").get(1, TimeUnit.SECONDS));
    }

    @Test
    void givenMalformedStateFrameThenRejectedWithoutPartialChange() throws Exception {
        NetworkState state = new NetworkState();
        state.apply(new NetworkState().playerJoined(UUID.randomUUID(), "A", "lobby"));
        assertEquals(1, state.playerCount());
        assertThrows(FrameException.class, () -> state.apply(Frame.of(FrameType.STATE_SNAPSHOT, new byte[]{0, 0, 0, 5})));
        assertEquals(1, state.playerCount());
    }
}
