package Vystorm.vystorm_core.network.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869). The JDK only ships an HKDF API from Java 24 on; Core targets Java 21.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class Hkdf {
    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) throw new IllegalArgumentException("Bad HKDF length " + length);
        try {
            byte[] prk = hmac(salt == null || salt.length == 0 ? new byte[HASH_LEN] : salt, ikm);
            byte[] out = new byte[length];
            byte[] previous = new byte[0];
            int written = 0;
            for (int counter = 1; written < length; counter++) {
                Mac mac = Mac.getInstance(HMAC);
                mac.init(new SecretKeySpec(prk, HMAC));
                mac.update(previous);
                if (info != null) mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int take = Math.min(previous.length, length - written);
                System.arraycopy(previous, 0, out, written, take);
                written += take;
            }
            return out;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 unavailable", failure);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));
        return mac.doFinal(data);
    }
}
