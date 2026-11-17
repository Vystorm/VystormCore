package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.network.core.ChannelMessage;
import Vystorm.vystorm_core.network.core.KeyFile;
import Vystorm.vystorm_core.network.core.KeySources;
import Vystorm.vystorm_core.network.core.NetworkKey;
import Vystorm.vystorm_core.network.core.Pairing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ParticipantSettingsTest {
    @TempDir Path dir;

    private Path paperGlobal(String secret) throws Exception {
        Path file = dir.resolve("paper-global.yml");
        Files.writeString(file, "proxies:\n  velocity:\n    enabled: true\n    secret: '" + secret + "'\n");
        return file;
    }

    private ParticipantSettings settings(String manualKey, Path paperGlobal) {
        return new ParticipantSettings("auto", manualKey, "", "", 0, paperGlobal, dir.resolve("network"));
    }

    @Test
    void givenAllSourcesThenManualKeyWinsThenVelocityThenPairedFile() throws Exception {
        NetworkKey manual = KeySources.fromVelocitySecret("manual");
        String manualB64 = Base64.getEncoder().encodeToString(manual.bytes());
        NetworkKey paired = KeySources.fromVelocitySecret("paired");
        KeyFile.save(dir.resolve("network/network.key"), paired);

        assertEquals(Optional.of(manual), settings(manualB64, paperGlobal("velo")).key());
        assertEquals("config", settings(manualB64, paperGlobal("velo")).keySource());
        assertEquals(Optional.of(KeySources.fromVelocitySecret("velo")), settings("", paperGlobal("velo")).key());
        assertEquals("velocity", settings("", paperGlobal("velo")).keySource());
        assertEquals(Optional.of(paired), settings("", dir.resolve("missing.yml")).key());
        assertEquals("paired", settings("", dir.resolve("missing.yml")).keySource());
    }

    @Test
    void givenNoSourceThenNoKey() {
        ParticipantSettings s = settings("", dir.resolve("missing.yml"));
        assertTrue(s.key().isEmpty());
        assertEquals("none", s.keySource());
    }

    @Test
    void givenAnnouncedIdentityThenRememberedAcrossRestarts() throws Exception {
        ParticipantSettings s = settings("", dir.resolve("missing.yml"));
        assertTrue(s.serverName().isEmpty());
        assertTrue(s.learn(new ChannelMessage.Identity("lobby", "127.0.0.1", 25599)));
        assertFalse(s.learn(new ChannelMessage.Identity("lobby", "127.0.0.1", 25599)), "unchanged identity is not rewritten");
        ParticipantSettings restarted = settings("", dir.resolve("missing.yml"));
        assertEquals(Optional.of("lobby"), restarted.serverName());
        assertEquals(25599, restarted.hubAddress().orElseThrow().getPort());
    }

    @Test
    void givenConfigValuesThenTheyWinOverAnnouncedIdentity() throws Exception {
        ParticipantSettings s = new ParticipantSettings("true", "", "survival", "10.0.0.2", 26000,
                dir.resolve("missing.yml"), dir.resolve("network"));
        s.learn(new ChannelMessage.Identity("lobby", "127.0.0.1", 25599));
        assertEquals(Optional.of("survival"), s.serverName());
        assertEquals("10.0.0.2", s.hubAddress().orElseThrow().getHostString());
        assertEquals(26000, s.hubAddress().orElseThrow().getPort());
        assertEquals(ParticipantSettings.Mode.ON, s.mode());
    }

    @Test
    void givenEnabledFalseThenModeOff() {
        assertEquals(ParticipantSettings.Mode.OFF, new ParticipantSettings("false", "", "", "", 0, dir, dir).mode());
        assertEquals(ParticipantSettings.Mode.AUTO, new ParticipantSettings(null, "", "", "", 0, dir, dir).mode());
    }

    @Test
    void givenChannelMessagesThenRoundTripAndGarbageIgnored() {
        ChannelMessage.Identity id = new ChannelMessage.Identity("lobby", "127.0.0.1", 25599);
        assertEquals(Optional.of(id), ChannelMessage.decode(ChannelMessage.encode(id)));
        var offer = new ChannelMessage.Offer("lobby", Pairing.hubStart("proxy", "127.0.0.1", 25599, Clock.systemUTC()).offer());
        ChannelMessage back = ChannelMessage.decode(ChannelMessage.encode(offer)).orElseThrow();
        assertEquals("lobby", ((ChannelMessage.Offer) back).serverName());
        assertTrue(ChannelMessage.decode(new byte[]{1, 2, 3}).isEmpty());
        assertTrue(ChannelMessage.decode(null).isEmpty());
    }

    @Test
    void givenNoProxyThenDisconnectedNetworkIsSafeNoop() throws Exception {
        var n = DisconnectedNetwork.INSTANCE;
        assertFalse(n.connected());
        assertEquals("", n.serverName());
        assertTrue(n.servers().isEmpty());
        assertTrue(n.serverOf(UUID.randomUUID()).isEmpty());
        assertFalse(n.sendPlayer(UUID.randomUUID(), "lobby").get());
        assertDoesNotThrow(() -> n.send("lobby", "myplugin:data", new byte[0]));
        assertDoesNotThrow(() -> n.broadcast("myplugin:data", new byte[0]));
        assertDoesNotThrow(() -> n.subscribe("myplugin:data", (from, data) -> { }).close());
        assertThrows(IllegalArgumentException.class, () -> n.send("lobby", "vystorm:broadcast", new byte[0]));
    }

    @Test
    void givenCoreApiThenNetworkCapabilityRegisteredAndNetworkNeverNull() {
        assertTrue(CoreApi.has(CoreApi.Capability.NETWORK));
        assertEquals(Optional.of("0.53.0"), CoreApi.since("network"));
        assertNotNull(CoreApi.network());
        assertFalse(CoreApi.network().connected());
    }
}
