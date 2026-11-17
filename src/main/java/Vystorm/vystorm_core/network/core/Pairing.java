package Vystorm.vystorm_core.network.core;

import javax.crypto.KeyAgreement;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Pairing for proxies without a shared Velocity secret (spec section 2, rules 1–3).
 * Both ends run X25519 and derive the same network key plus a 6-character confirmation code. The participant only
 * keeps the key after the admin types {@code /vnet pair accept <code>}; the hub only keeps it after that server
 * authenticates with it.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class Pairing {
    /** Offers and unconfirmed results expire after this long. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String ALGORITHM = "X25519";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pairing() {}

    public static HubSide hubStart(String hubId, String host, int port, Clock clock) {
        KeyPair pair = generate();
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        PairingOffer offer = new PairingOffer(HexFormat.of().formatHex(id), hubId, host, port,
                pair.getPublic().getEncoded(), clock.millis());
        return new HubSide(pair, offer, clock);
    }

    public static ClientSide clientAccept(PairingOffer offer, Clock clock) throws GeneralSecurityException {
        KeyPair pair = generate();
        byte[] shared = agree(pair, offer.hubPublicKey());
        byte[] ownPublic = pair.getPublic().getEncoded();
        return new ClientSide(offer, ownPublic, NetworkKey.derive(shared),
                confirmationCode(offer.hubPublicKey(), ownPublic), clock);
    }

    /** 6 characters from SHA-256 over both public keys in sorted order; the same on both ends. */
    public static String confirmationCode(byte[] publicA, byte[] publicB) {
        try {
            byte[] first = Arrays.compare(publicA, publicB) <= 0 ? publicA : publicB;
            byte[] second = first == publicA ? publicB : publicA;
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(first);
            byte[] digest = sha.digest(second);
            StringBuilder code = new StringBuilder(6);
            for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET[(digest[i] & 0xff) % CODE_ALPHABET.length]);
            return code.toString();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("X25519 unavailable", impossible);
        }
    }

    private static byte[] agree(KeyPair own, byte[] peerEncoded) throws GeneralSecurityException {
        PublicKey peer = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(peerEncoded));
        KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
        agreement.init(own.getPrivate());
        agreement.doPhase(peer, true);
        return agreement.generateSecret();
    }

    private static boolean expired(long createdAt, Clock clock) {
        return clock.millis() - createdAt > LIFETIME.toMillis();
    }

    /** The proxy's half of one offer to one server. Only the first reply is ever used. */
    public static final class HubSide {
        private final KeyPair pair;
        private final PairingOffer offer;
        private final Clock clock;
        private NetworkKey key;
        private String code;
        private boolean compromised;

        private HubSide(KeyPair pair, PairingOffer offer, Clock clock) {
            this.pair = pair;
            this.offer = offer;
            this.clock = clock;
        }

        public PairingOffer offer() { return offer; }

        /**
         * Handles a participant's reply. A second reply for the same offer means someone else answered too: the offer
         * is then burnt and nothing is returned, so the admin has to start over.
         */
        public synchronized Optional<NetworkKey> acceptReply(byte[] clientPublicKey) {
            if (compromised || expired()) return Optional.empty();
            if (key != null) {
                compromised = true;
                key = null;
                return Optional.empty();
            }
            try {
                key = NetworkKey.derive(agree(pair, clientPublicKey));
                code = confirmationCode(offer.hubPublicKey(), clientPublicKey);
                return Optional.of(key);
            } catch (GeneralSecurityException | RuntimeException badKey) {
                return Optional.empty();
            }
        }

        public synchronized Optional<String> code() { return Optional.ofNullable(code); }
        public synchronized Optional<NetworkKey> key() { return compromised || expired() ? Optional.empty() : Optional.ofNullable(key); }
        public synchronized boolean compromised() { return compromised; }
        public boolean expired() { return Pairing.expired(offer.createdAtMillis(), clock); }
    }

    /** The participant's half: holds the derived key until the admin confirms the code. */
    public static final class ClientSide {
        private final PairingOffer offer;
        private final byte[] publicKey;
        private final NetworkKey key;
        private final String code;
        private final Clock clock;
        private final long createdAt;

        private ClientSide(PairingOffer offer, byte[] publicKey, NetworkKey key, String code, Clock clock) {
            this.offer = offer;
            this.publicKey = publicKey;
            this.key = key;
            this.code = code;
            this.clock = clock;
            this.createdAt = clock.millis();
        }

        public PairingOffer offer() { return offer; }
        public byte[] publicKey() { return publicKey.clone(); }
        public NetworkKey key() { return key; }
        public String code() { return code; }
        public boolean expired() { return Pairing.expired(createdAt, clock); }
    }
}
