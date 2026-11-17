package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class HubRuntimeTest {
    @TempDir Path dir;
    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach void stop() { cleanup.forEach(Runnable::run); }

    private HubRuntime runtime(Optional<String> velocitySecret) throws Exception {
        Files.writeString(dir.resolve(HubRuntime.SETTINGS_FILE), "bind-host=127.0.0.1\nport=0\nhub-id=test-proxy\n");
        ProxyBridge bridge = new ProxyBridge() {
            @Override public List<String> configuredServers() { return List.of("lobby", "survival"); }
            @Override public CompletableFuture<Boolean> sendPlayer(UUID player, String server) { return CompletableFuture.completedFuture(true); }
        };
        HubRuntime runtime = new HubRuntime(dir, velocitySecret, bridge, NetworkLog.SILENT, Clock.systemUTC());
        assertTrue(runtime.start());
        cleanup.add(runtime::stop);
        return runtime;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not met within 5 s");
            Thread.sleep(20);
        }
    }

    private HubClient connect(HubRuntime runtime, String name, NetworkKey key) {
        HubClient client = new HubClient(() -> Optional.of(new InetSocketAddress("127.0.0.1", runtime.server().orElseThrow().port())),
                name, () -> Optional.of(key), new ParticipantNode(Runnable::run, NetworkLog.SILENT), NetworkLog.SILENT,
                new HubConfig("127.0.0.1", 0, List.of(), 64, Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(3)),
                new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), new Random(5)));
        client.start();
        cleanup.add(client::stop);
        return client;
    }

    @Test
    void givenNoSharedKeyWhenPlayerReachesServerThenIdentityAndOfferAndConfirmedPairingIsStored() throws Exception {
        HubRuntime runtime = runtime(Optional.empty());
        List<byte[]> sent = new ArrayList<>();
        runtime.playerReachedServer(UUID.randomUUID(), "Esoren", "lobby", true, sent::add);
        assertEquals(2, sent.size());
        ChannelMessage.Identity identity = (ChannelMessage.Identity) ChannelMessage.decode(sent.get(0)).orElseThrow();
        assertEquals("lobby", identity.serverName());
        ChannelMessage.Offer offer = (ChannelMessage.Offer) ChannelMessage.decode(sent.get(1)).orElseThrow();
        assertEquals("test-proxy", offer.offer().hubId());

        // Participant side: accept the offer and answer on the hub port.
        Pairing.ClientSide side = new PairingGate(Clock.systemUTC()).acceptOffer(offer.offer(), false).orElseThrow();
        try (Socket socket = new Socket("127.0.0.1", identity.hubPort())) {
            Handshake.sendPairReply(new BufferedInputStream(socket.getInputStream()), new BufferedOutputStream(socket.getOutputStream()),
                    offer.offer(), "lobby", side.publicKey());
        }
        assertEquals(List.of("lobby " + side.code()), runtime.pendingPairings(), "both consoles show the same code");
        assertFalse(Files.exists(dir.resolve("servers/lobby.key")), "nothing stored before the server authenticates");

        // After /vnet pair accept the server connects with the derived key; only then the hub stores it.
        HubClient client = connect(runtime, "lobby", side.key());
        await(client::connected);
        await(() -> Files.exists(dir.resolve("servers/lobby.key")));
        assertEquals(Optional.of(side.key()), KeyFile.load(dir.resolve("servers/lobby.key")));
        assertTrue(runtime.pendingPairings().isEmpty());

        // Connected servers get neither identity nor offer again.
        List<byte[]> again = new ArrayList<>();
        runtime.playerReachedServer(UUID.randomUUID(), "Other", "lobby", true, again::add);
        assertTrue(again.isEmpty());
    }

    @Test
    void givenReplyWithWrongOfferIdThenRefused() throws Exception {
        HubRuntime runtime = runtime(Optional.empty());
        List<byte[]> sent = new ArrayList<>();
        runtime.playerReachedServer(UUID.randomUUID(), "Esoren", "lobby", true, sent::add);
        ChannelMessage.Offer offer = (ChannelMessage.Offer) ChannelMessage.decode(sent.get(1)).orElseThrow();
        PairingOffer forged = new PairingOffer("bogus", offer.offer().hubId(), offer.offer().hubHost(), offer.offer().hubPort(),
                offer.offer().hubPublicKey(), offer.offer().createdAtMillis());
        Pairing.ClientSide side = new PairingGate(Clock.systemUTC()).acceptOffer(offer.offer(), false).orElseThrow();
        try (Socket socket = new Socket("127.0.0.1", offer.offer().hubPort())) {
            HandshakeException refused = assertThrows(HandshakeException.class, () -> Handshake.sendPairReply(
                    new BufferedInputStream(socket.getInputStream()), new BufferedOutputStream(socket.getOutputStream()),
                    forged, "lobby", side.publicKey()));
            assertEquals(HandshakeException.Reason.AUTH, refused.reason());
        }
        assertTrue(runtime.pendingPairings().isEmpty());
    }

    @Test
    void givenUnpairedServerThenHandshakeWithAnyKeyRefused() throws Exception {
        HubRuntime runtime = runtime(Optional.empty());
        assertTrue(runtime.keyFor("lobby").isEmpty());
        HubClient client = connect(runtime, "lobby", KeySources.fromVelocitySecret("guess"));
        Thread.sleep(500);
        assertFalse(client.connected());
    }

    @Test
    void givenVelocitySecretThenEveryServerSharesKeyAndNoOfferIsSent() throws Exception {
        HubRuntime runtime = runtime(Optional.of("velocity-secret"));
        assertTrue(runtime.sharedKey());
        List<byte[]> sent = new ArrayList<>();
        runtime.playerReachedServer(UUID.randomUUID(), "Esoren", "survival", true, sent::add);
        assertEquals(1, sent.size());
        assertInstanceOf(ChannelMessage.Identity.class, ChannelMessage.decode(sent.get(0)).orElseThrow());
        HubClient client = connect(runtime, "survival", KeySources.fromVelocitySecret("velocity-secret"));
        await(client::connected);
        assertFalse(Files.exists(dir.resolve("servers/survival.key")));
    }

    @Test
    void givenInvalidServerNameThenNoKeyAndNoMessages() throws Exception {
        HubRuntime runtime = runtime(Optional.of("s"));
        assertTrue(runtime.keyFor("../evil").isEmpty());
        List<byte[]> sent = new ArrayList<>();
        runtime.playerReachedServer(UUID.randomUUID(), "x", "../evil", true, sent::add);
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenNoSettingsFileThenDefaultsWrittenWithLocalhostOnly() throws Exception {
        Path fresh = dir.resolve("fresh");
        HubRuntime runtime = new HubRuntime(fresh, Optional.empty(), new ProxyBridge() {
            @Override public List<String> configuredServers() { return List.of(); }
            @Override public CompletableFuture<Boolean> sendPlayer(UUID p, String s) { return CompletableFuture.completedFuture(false); }
        }, NetworkLog.SILENT, Clock.systemUTC());
        assertTrue(Files.exists(fresh.resolve(HubRuntime.SETTINGS_FILE)));
        assertEquals("127.0.0.1", runtime.config().bindHost());
        assertEquals(HubConfig.DEFAULT_PORT, runtime.config().port());
    }

    @Test
    void givenVelocityTomlThenModernForwardingDetected() throws Exception {
        Files.writeString(dir.resolve("velocity.toml"), "player-info-forwarding-mode = \"modern\"\n");
        assertTrue(KeySources.velocityModernForwarding(dir.resolve("velocity.toml")));
        Files.writeString(dir.resolve("velocity.toml"), "player-info-forwarding-mode = \"legacy\"\n");
        assertFalse(KeySources.velocityModernForwarding(dir.resolve("velocity.toml")));
    }
}
