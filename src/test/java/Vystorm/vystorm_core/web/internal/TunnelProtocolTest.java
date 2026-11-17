package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Kryptografie des Gateway-Tunnels; die Testvektoren stehen identisch in den Tests des Gateways (protocol.test.js). */
class TunnelProtocolTest {
    static final byte[] SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
    static final String CORE_NONCE = "A".repeat(43);
    static final String GATEWAY_NONCE = "B".repeat(43);
    static final long TS = 1_700_000_000_000L;

    @Test
    void givenFixedInputs_whenSigned_thenMatchesSharedTestVectors() {
        assertEquals("lInWKNwKecJo_VS62KOO1uhq9KRVh_pyi-ngr4D9Ckg", TunnelProtocol.helloSignature(SECRET, TS, CORE_NONCE));
        assertEquals("eNiAgx8ys2hYPtHISJ18KduBK59afN3TILqn3km5M6s", TunnelProtocol.welcomeSignature(SECRET, CORE_NONCE, GATEWAY_NONCE, TS));
        byte[] key = TunnelProtocol.sessionKey(SECRET, CORE_NONCE, GATEWAY_NONCE);
        assertEquals("H2zMarCnvZa2tK5RW2ha00r1N6zBKEwNHO0c5rOddS0", Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        assertEquals("uTGBAOsE1L_uiTI0gb-e-AGUtha2GQacUkQ4DUn6Qcw.{\"t\":\"ping\",\"seq\":1}",
                TunnelProtocol.seal(key, TunnelProtocol.G2C, "{\"t\":\"ping\",\"seq\":1}"));
    }

    private static JsonObject welcome(long ts, String sig) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "welcome");
        o.addProperty("v", 1);
        o.addProperty("ts", ts);
        o.addProperty("nonce", GATEWAY_NONCE);
        o.addProperty("sig", sig);
        return o;
    }

    @Test
    void givenValidWelcome_whenVerified_thenSessionKeyDerived() {
        byte[] key = TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, welcome(TS, TunnelProtocol.welcomeSignature(SECRET, CORE_NONCE, GATEWAY_NONCE, TS)), TS + 1000, 30_000);
        assertArrayEquals(TunnelProtocol.sessionKey(SECRET, CORE_NONCE, GATEWAY_NONCE), key);
    }

    @Test
    void givenWelcomeWithWrongSecretOrReplayedForOtherNonce_whenVerified_thenRejected() {
        byte[] other = "another-secret-0123456789-abcdefghijklmnopqrstuv".getBytes(StandardCharsets.UTF_8);
        String forged = TunnelProtocol.welcomeSignature(other, CORE_NONCE, GATEWAY_NONCE, TS);
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, welcome(TS, forged), TS, 30_000));
        // Mitgeschnittenes welcome einer früheren Verbindung (andere Core-Nonce) ist wertlos.
        String replay = TunnelProtocol.welcomeSignature(SECRET, "C".repeat(43), GATEWAY_NONCE, TS);
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, welcome(TS, replay), TS, 30_000));
    }

    @Test
    void givenWelcomeOutsideClockWindow_whenVerified_thenRejected() {
        String sig = TunnelProtocol.welcomeSignature(SECRET, CORE_NONCE, GATEWAY_NONCE, TS);
        var e = assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, welcome(TS, sig), TS + 31_000, 30_000));
        assertTrue(e.getMessage().contains("timestamp"));
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, welcome(TS, sig), TS - 31_000, 30_000));
    }

    @Test
    void givenMalformedWelcome_whenVerified_thenRejectedWithoutOtherExceptions() {
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, new JsonObject(), TS, 30_000));
        JsonObject wrongType = welcome(TS, "x");
        wrongType.addProperty("t", "req");
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.verifyWelcome(SECRET, CORE_NONCE, wrongType, TS, 30_000));
    }

    @Test
    void givenSealedFrames_whenOpened_thenMacDirectionAndSequenceEnforced() {
        byte[] key = TunnelProtocol.sessionKey(SECRET, CORE_NONCE, GATEWAY_NONCE);
        String frame = TunnelProtocol.seal(key, TunnelProtocol.G2C, "{\"t\":\"ping\",\"seq\":1}");
        assertEquals("ping", TunnelProtocol.openFrame(key, TunnelProtocol.G2C, frame, 1).get("t").getAsString());
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.openFrame(key, TunnelProtocol.G2C, frame, 2), "replayed/out of order");
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.openFrame(key, TunnelProtocol.C2G, frame, 1), "reflected to other direction");
        String tampered = frame.replace("ping", "pong");
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.openFrame(key, TunnelProtocol.G2C, tampered, 1));
        byte[] otherKey = TunnelProtocol.sessionKey(SECRET, CORE_NONCE, "D".repeat(43));
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.openFrame(otherKey, TunnelProtocol.G2C, frame, 1), "other connection");
        assertThrows(IllegalStateException.class, () -> TunnelProtocol.openFrame(key, TunnelProtocol.G2C, "garbage", 1));
    }

    @Test
    void givenOversizedBrowserRequest_whenParsedFromTunnel_thenAnsweredWithStatusInsteadOfKillingTheTunnel() throws Exception {
        // Früher warf ein zu langes Ziel oder zu viele Header eine Ausnahme → "protocol violation" → Tunnel für alle zu.
        WebSettings settings = WebFixture.settings("enabled: true\nlimits:\n  max-body-bytes: 1024\n  max-headers: 16\n");
        com.google.gson.JsonObject req = new com.google.gson.JsonObject();
        req.addProperty("t", "req");
        req.addProperty("method", "GET");
        req.addProperty("path", "/" + "a".repeat(5000));
        req.addProperty("ip", "203.0.113.5");
        assertEquals(414, GatewayTunnel.parseRequest(req, settings).status());

        req.addProperty("path", "/x");
        com.google.gson.JsonObject headers = new com.google.gson.JsonObject();
        for (int i = 0; i < 17; i++) headers.addProperty("h" + i, "v");
        req.add("headers", headers);
        assertEquals(431, GatewayTunnel.parseRequest(req, settings).status());

        req.add("headers", new com.google.gson.JsonObject());
        req.addProperty("body", java.util.Base64.getEncoder().encodeToString(new byte[2048]));
        assertEquals(413, GatewayTunnel.parseRequest(req, settings).status());

        req.addProperty("body", java.util.Base64.getEncoder().encodeToString(new byte[10]));
        GatewayTunnel.ParsedRequest ok = GatewayTunnel.parseRequest(req, settings);
        assertEquals(0, ok.status());
        assertEquals("203.0.113.5", ok.data().clientIp());
        assertEquals(10, ok.data().body().length);

        req.addProperty("method", "get\r\n");
        assertThrows(IllegalStateException.class, () -> GatewayTunnel.parseRequest(req, settings), "malformed gateway frame stays a violation");
    }
}
