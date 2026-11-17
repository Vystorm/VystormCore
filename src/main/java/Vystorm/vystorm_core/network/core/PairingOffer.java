package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What the proxy sends to a server without key over the {@code vystorm:net} plugin-messaging channel.
 * Only the hub's public key travels; the network key is derived on both ends (X25519) and never sent.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record PairingOffer(String offerId, String hubId, String hubHost, int hubPort, byte[] hubPublicKey, long createdAtMillis) {
    private static final int MAGIC = 0x56504F31; // "VPO1"

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeUTF(offerId);
            out.writeUTF(hubId);
            out.writeUTF(hubHost);
            out.writeInt(hubPort);
            out.writeShort(hubPublicKey.length);
            out.write(hubPublicKey);
            out.writeLong(createdAtMillis);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static PairingOffer decode(byte[] data) throws FrameException {
        if (data == null || data.length > 4096) throw new FrameException("Bad pairing offer");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) throw new FrameException("Not a pairing offer");
            String offerId = in.readUTF();
            String hubId = in.readUTF();
            String host = in.readUTF();
            int port = in.readInt();
            int keyLength = in.readUnsignedShort();
            if (keyLength == 0 || keyLength > 256) throw new FrameException("Bad hub key length");
            byte[] key = new byte[keyLength];
            in.readFully(key);
            long created = in.readLong();
            if (in.available() != 0 || port <= 0 || port > 65535 || offerId.isBlank() || host.isBlank()) {
                throw new FrameException("Bad pairing offer");
            }
            return new PairingOffer(offerId, hubId, host, port, key, created);
        } catch (FrameException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new FrameException("Bad pairing offer", failure);
        }
    }
}
