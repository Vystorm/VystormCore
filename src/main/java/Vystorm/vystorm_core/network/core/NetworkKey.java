package Vystorm.vystorm_core.network.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The 32-byte network key shared by hub and participants. It never leaves this object in text form:
 * {@link #toString()} and logs only ever show the {@link #fingerprint()}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class NetworkKey {
    public static final int LENGTH = 32;
    /** HKDF salt and info used for every derived network key (spec section 2). */
    public static final byte[] SALT = "vystorm-network-v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] INFO = "network-key".getBytes(StandardCharsets.US_ASCII);

    private final byte[] bytes;

    private NetworkKey(byte[] bytes) { this.bytes = bytes; }

    public static NetworkKey of(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) throw new IllegalArgumentException("Network key must be 32 bytes");
        return new NetworkKey(bytes.clone());
    }

    /** Derives the network key from shared secret material (Velocity secret or X25519 result). */
    public static NetworkKey derive(byte[] secretMaterial) {
        return new NetworkKey(Hkdf.derive(secretMaterial, SALT, INFO, LENGTH));
    }

    public byte[] bytes() { return bytes.clone(); }

    /** First 8 hex characters of SHA-256(key); safe to log. */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof NetworkKey key && MessageDigest.isEqual(bytes, key.bytes);
    }

    @Override public int hashCode() { return fingerprint().hashCode(); }

    @Override public String toString() { return "NetworkKey[" + fingerprint() + "]"; }
}
