package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Mutual authentication without sending the key (spec section 2):
 * <ol>
 *   <li>client → hub: HELLO {version, nonce_a}, from = server name</li>
 *   <li>hub → client: HELLO {version, nonce_b} (or ERROR + close on wrong version / rejected name)</li>
 *   <li>client → hub: PROOF = HMAC(key, "vystorm-net-v1" || "client" || nonce_a || nonce_b || name)</li>
 *   <li>hub → client: PROOF = HMAC(key, "vystorm-net-v1" || "hub" || nonce_a || nonce_b || name)</li>
 *   <li>session keys = HKDF(key, nonce_a || nonce_b, "c2h" / "h2c"); hub sends an encrypted WELCOME</li>
 * </ol>
 * Proofs are compared in constant time. Handshake records are plain length-prefixed frames; the caller sets the
 * socket timeout (spec: 5 s).
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class Handshake {
    public static final int PROTOCOL_VERSION = 1;
    private static final byte[] LABEL = "vystorm-net-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE = 32;
    private static final int MAX_HANDSHAKE_RECORD = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Handshake() {}

    public static SecureChannel client(NetworkKey key, String serverName, InputStream rawIn, OutputStream rawOut) throws IOException {
        return client(key, serverName, rawIn, rawOut, PROTOCOL_VERSION);
    }

    static SecureChannel client(NetworkKey key, String serverName, InputStream rawIn, OutputStream rawOut, int version) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        DataOutputStream out = new DataOutputStream(rawOut);
        byte[] nonceA = nonce();
        writePlain(out, new Frame(FrameType.HELLO, "", serverName, "", hello(version, nonceA)));
        Frame reply = readPlain(in);
        failOnError(reply);
        byte[] nonceB = parseHello(reply);
        writePlain(out, Frame.of(FrameType.PROOF, proof(key, "client", nonceA, nonceB, serverName)));
        Frame hubProof = readPlain(in);
        failOnError(hubProof);
        expect(hubProof, FrameType.PROOF);
        if (!MessageDigest.isEqual(hubProof.payload(), proof(key, "hub", nonceA, nonceB, serverName))) {
            throw new HandshakeException(HandshakeException.Reason.AUTH, "Hub could not prove it knows the network key");
        }
        SecureChannel channel = channel(key, nonceA, nonceB, in, out, true, "");
        Frame welcome = channel.receive();
        expect(welcome, FrameType.WELCOME);
        return channel;
    }

    public static SecureChannel hub(NetworkKey key, Predicate<String> nameAllowed, InputStream rawIn, OutputStream rawOut) throws IOException {
        return hub(name -> nameAllowed.test(name) ? java.util.Optional.of(key) : java.util.Optional.empty(), rawIn, rawOut);
    }

    /**
     * Hub side with one key per server (paired servers each have their own; Velocity-secret and manual keys are the
     * same for all). An empty result rejects the server name.
     */
    public static SecureChannel hub(java.util.function.Function<String, java.util.Optional<NetworkKey>> keyFor,
                                    InputStream rawIn, OutputStream rawOut) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        return hub(readPlain(in), keyFor, in, rawOut);
    }

    /** Reads the first plain record so the hub can tell a HELLO from a PAIR_REPLY before choosing a path. */
    public static Frame readFirst(InputStream rawIn) throws IOException {
        return readPlain(rawIn instanceof DataInputStream d ? d : new DataInputStream(rawIn));
    }

    /** Continues the hub handshake after {@link #readFirst} returned a HELLO. Use the same input stream. */
    public static SecureChannel hub(Frame hello, java.util.function.Function<String, java.util.Optional<NetworkKey>> keyFor,
                                    InputStream rawIn, OutputStream rawOut) throws IOException {
        DataInputStream in = rawIn instanceof DataInputStream d ? d : new DataInputStream(rawIn);
        DataOutputStream out = new DataOutputStream(rawOut);
        expect(hello, FrameType.HELLO);
        int version = hello.payload().length > 0 ? hello.payload()[0] & 0xff : -1;
        if (version != PROTOCOL_VERSION) {
            writeError(out, "protocol " + PROTOCOL_VERSION + " required");
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL, "Peer speaks protocol " + version + ", expected " + PROTOCOL_VERSION);
        }
        byte[] nonceA = parseHello(hello);
        String name = hello.from();
        java.util.Optional<NetworkKey> found = name.isBlank() ? java.util.Optional.empty() : keyFor.apply(name);
        if (found.isEmpty()) {
            writeError(out, "server name rejected");
            throw new HandshakeException(HandshakeException.Reason.NAME_REJECTED, "Server name rejected: " + name);
        }
        NetworkKey key = found.get();
        byte[] nonceB = nonce();
        writePlain(out, Frame.of(FrameType.HELLO, hello(PROTOCOL_VERSION, nonceB)));
        Frame clientProof = readPlain(in);
        expect(clientProof, FrameType.PROOF);
        if (!MessageDigest.isEqual(clientProof.payload(), proof(key, "client", nonceA, nonceB, name))) {
            writeError(out, "authentication failed");
            throw new HandshakeException(HandshakeException.Reason.AUTH, "Server '" + name + "' could not prove it knows the network key");
        }
        writePlain(out, Frame.of(FrameType.PROOF, proof(key, "hub", nonceA, nonceB, name)));
        SecureChannel channel = channel(key, nonceA, nonceB, in, out, false, name);
        channel.send(Frame.of(FrameType.WELCOME, new byte[0]));
        return channel;
    }

    /**
     * Participant: answers a pairing offer on the hub port with its public key (unauthenticated; nothing secret is
     * sent) and waits for the hub's acknowledgement. The hub has to accept exactly this offer id.
     */
    public static void sendPairReply(InputStream rawIn, OutputStream rawOut, PairingOffer offer, String serverName,
                                     byte[] publicKey) throws IOException {
        DataInputStream in = rawIn instanceof DataInputStream d ? d : new DataInputStream(rawIn);
        DataOutputStream out = new DataOutputStream(rawOut);
        writePlain(out, new Frame(FrameType.PAIR_REPLY, offer.offerId(), serverName, "", publicKey));
        Frame answer = readPlain(in);
        failOnError(answer);
        expect(answer, FrameType.WELCOME);
    }

    /** Hub: acknowledges or refuses a PAIR_REPLY read via {@link #readFirst}. */
    public static void answerPairReply(OutputStream rawOut, boolean accepted) {
        DataOutputStream out = new DataOutputStream(rawOut);
        if (accepted) {
            try { writePlain(out, Frame.of(FrameType.WELCOME, new byte[0])); } catch (IOException ignored) { /* peer gone */ }
        } else {
            writeError(out, "pairing refused");
        }
    }

    private static SecureChannel channel(NetworkKey key, byte[] nonceA, byte[] nonceB, DataInputStream in,
                                         DataOutputStream out, boolean client, String peer) {
        byte[] salt = concat(nonceA, nonceB);
        byte[] c2h = Hkdf.derive(key.bytes(), salt, "c2h".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] h2c = Hkdf.derive(key.bytes(), salt, "h2c".getBytes(StandardCharsets.US_ASCII), 32);
        return client
                ? new SecureChannel(in, out, c2h, h2c, SecureChannel.DIR_CLIENT_TO_HUB, SecureChannel.DIR_HUB_TO_CLIENT, peer)
                : new SecureChannel(in, out, h2c, c2h, SecureChannel.DIR_HUB_TO_CLIENT, SecureChannel.DIR_CLIENT_TO_HUB, peer);
    }

    static byte[] proof(NetworkKey key, String role, byte[] nonceA, byte[] nonceB, String name) {
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(LABEL);
            data.write(role.getBytes(StandardCharsets.US_ASCII));
            data.write(nonceA);
            data.write(nonceB);
            data.write(name.getBytes(StandardCharsets.UTF_8));
            return Hkdf.hmac(key.bytes(), data.toByteArray());
        } catch (IOException | GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] hello(int version, byte[] nonce) {
        byte[] payload = new byte[1 + NONCE];
        payload[0] = (byte) version;
        System.arraycopy(nonce, 0, payload, 1, NONCE);
        return payload;
    }

    private static byte[] parseHello(Frame hello) throws HandshakeException {
        expect(hello, FrameType.HELLO);
        byte[] p = hello.payload();
        if (p.length != 1 + NONCE) throw new HandshakeException(HandshakeException.Reason.MALFORMED, "Bad HELLO");
        if ((p[0] & 0xff) != PROTOCOL_VERSION) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL, "Peer speaks protocol " + (p[0] & 0xff));
        }
        return Arrays.copyOfRange(p, 1, p.length);
    }

    private static void failOnError(Frame frame) throws HandshakeException {
        if (frame.type() != FrameType.ERROR) return;
        String reason = new String(frame.payload(), StandardCharsets.UTF_8);
        HandshakeException.Reason kind = reason.startsWith("protocol") ? HandshakeException.Reason.PROTOCOL
                : reason.startsWith("server name") ? HandshakeException.Reason.NAME_REJECTED
                : HandshakeException.Reason.AUTH;
        throw new HandshakeException(kind, "Hub refused the connection: " + reason);
    }

    private static void expect(Frame frame, FrameType type) throws HandshakeException {
        if (frame.type() != type) {
            throw new HandshakeException(HandshakeException.Reason.MALFORMED, "Expected " + type + " but got " + frame.type());
        }
    }

    private static void writeError(DataOutputStream out, String message) {
        try { writePlain(out, Frame.of(FrameType.ERROR, message.getBytes(StandardCharsets.UTF_8))); }
        catch (IOException ignored) { /* peer may already be gone */ }
    }

    private static void writePlain(DataOutputStream out, Frame frame) throws IOException {
        byte[] data = FrameCodec.encode(frame);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private static Frame readPlain(DataInputStream in) throws IOException {
        return FrameCodec.decode(SecureChannel.readRecord(in, MAX_HANDSHAKE_RECORD));
    }

    private static byte[] nonce() {
        byte[] n = new byte[NONCE];
        RANDOM.nextBytes(n);
        return n;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
