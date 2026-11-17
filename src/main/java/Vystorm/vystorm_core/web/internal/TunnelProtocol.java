package Vystorm.vystorm_core.web.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kryptografie des Gateway-Tunnels (Version 1), identisch zu {@code web-gateway/src/protocol.js}.
 *
 * <ol>
 *   <li>Core → Gateway (HTTP-Upgrade): {@code X-Vystorm-Ts}, {@code X-Vystorm-Nonce},
 *       {@code X-Vystorm-Signature = HMAC(secret, "vystorm-tunnel-v1\ncore-hello\n" + ts + "\n" + nonce)}.
 *       Das Gateway prüft Zeitfenster, Einmaligkeit der Nonce und Signatur, bevor es das Upgrade annimmt.</li>
 *   <li>Gateway → Core (erste Nachricht, JSON): {@code {"t":"welcome","v":1,"ts":…,"nonce":gN,"sig":…}} mit
 *       {@code sig = HMAC(secret, "vystorm-tunnel-v1\ngateway-welcome\n" + coreNonce + "\n" + gN + "\n" + ts)}. Die Bindung
 *       an Cores frische Nonce beweist, dass das Gateway das Geheimnis kennt, und verhindert Replays.</li>
 *   <li>Sitzungsschlüssel {@code K = HMAC(secret, "vystorm-tunnel-v1\nsession\n" + coreNonce + "\n" + gN)}.</li>
 *   <li>Alle weiteren Nachrichten: {@code <mac>.<json>} mit {@code mac = HMAC(K, richtung + "\n" + json)} (richtung
 *       {@code c2g}/{@code g2c}) und einer lückenlos um 1 steigenden {@code seq} je Richtung, beginnend bei 1.</li>
 * </ol>
 * Alle Vergleiche laufen in konstanter Zeit. Core-intern.
 *
 * @since 0.17.0
 */
public final class TunnelProtocol {
    public static final String VERSION = "vystorm-tunnel-v1";
    public static final String C2G = "c2g";
    public static final String G2C = "g2c";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private TunnelProtocol() {}

    public static String helloSignature(byte[] secret, long ts, String nonce) {
        return B64.encodeToString(hmac(secret, VERSION + "\ncore-hello\n" + ts + "\n" + nonce));
    }

    public static String welcomeSignature(byte[] secret, String coreNonce, String gatewayNonce, long ts) {
        return B64.encodeToString(hmac(secret, VERSION + "\ngateway-welcome\n" + coreNonce + "\n" + gatewayNonce + "\n" + ts));
    }

    public static byte[] sessionKey(byte[] secret, String coreNonce, String gatewayNonce) {
        return hmac(secret, VERSION + "\nsession\n" + coreNonce + "\n" + gatewayNonce);
    }

    /** {@code mac.json} für eine Richtung. */
    public static String seal(byte[] key, String direction, String json) {
        return B64.encodeToString(hmac(key, direction + "\n" + json)) + "." + json;
    }

    /** Prüft und entfernt den MAC; null bei falschem MAC oder Format. */
    public static String open(byte[] key, String direction, String frame) {
        int dot = frame.indexOf('.');
        if (dot != 43) return null;
        String json = frame.substring(dot + 1);
        String expected = B64.encodeToString(hmac(key, direction + "\n" + json));
        return equal(expected, frame.substring(0, dot)) ? json : null;
    }

    /**
     * Prüft die {@code welcome}-Nachricht des Gateways gegen Cores Nonce und liefert den Sitzungsschlüssel.
     *
     * @throws IllegalStateException bei falschem Format, Zeitstempel außerhalb des Fensters oder falscher Signatur
     */
    public static byte[] verifyWelcome(byte[] secret, String coreNonce, com.google.gson.JsonObject welcome, long nowMs, long skewMs) {
        try {
            if (!"welcome".equals(welcome.get("t").getAsString()) || welcome.get("v").getAsInt() != 1) throw new IllegalStateException("expected welcome");
            long ts = welcome.get("ts").getAsLong();
            String gatewayNonce = welcome.get("nonce").getAsString();
            String sig = welcome.get("sig").getAsString();
            if (!SessionStore.wellFormed(gatewayNonce)) throw new IllegalStateException("bad gateway nonce");
            if (Math.abs(nowMs - ts) > skewMs) throw new IllegalStateException("welcome timestamp outside window");
            if (!equal(welcomeSignature(secret, coreNonce, gatewayNonce, ts), sig)) throw new IllegalStateException("gateway signature invalid");
            return sessionKey(secret, coreNonce, gatewayNonce);
        } catch (NullPointerException | ClassCastException | UnsupportedOperationException | IllegalArgumentException malformed) {
            throw new IllegalStateException("malformed welcome");
        }
    }

    /**
     * Öffnet eine versiegelte Nachricht und prüft die Sequenznummer.
     *
     * @throws IllegalStateException bei falschem MAC, kaputtem JSON oder falscher Sequenz
     */
    public static com.google.gson.JsonObject openFrame(byte[] key, String direction, String frame, long expectedSeq) {
        String json = open(key, direction, frame);
        if (json == null) throw new IllegalStateException("bad mac");
        com.google.gson.JsonObject o;
        try {
            o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (o.get("seq").getAsLong() != expectedSeq) throw new IllegalStateException("bad sequence");
            o.get("t").getAsString();
        } catch (RuntimeException malformed) {
            if (malformed instanceof IllegalStateException bad && "bad sequence".equals(bad.getMessage())) throw bad;
            throw new IllegalStateException("malformed frame");
        }
        return o;
    }

    public static boolean equal(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
