package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Messages on the {@code vystorm:net} plugin-messaging channel, proxy → server only (the proxy drops anything a
 * client sends on it). IDENTITY tells a server its name in the proxy config and where the hub listens; OFFER starts a
 * pairing. Neither carries a secret: a forged identity only makes the server try a hub that cannot pass the mutual
 * key proof, and offers need the admin's confirmation code.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public sealed interface ChannelMessage {
    String CHANNEL = "vystorm:net";

    record Identity(String serverName, String hubHost, int hubPort) implements ChannelMessage {}

    record Offer(String serverName, PairingOffer offer) implements ChannelMessage {}

    byte TYPE_IDENTITY = 1, TYPE_OFFER = 2;
    int MAGIC = 0x564E4D31; // "VNM1"

    static byte[] encode(ChannelMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            switch (message) {
                case Identity id -> {
                    out.writeByte(TYPE_IDENTITY);
                    out.writeUTF(id.serverName());
                    out.writeUTF(id.hubHost());
                    out.writeInt(id.hubPort());
                }
                case Offer offer -> {
                    out.writeByte(TYPE_OFFER);
                    out.writeUTF(offer.serverName());
                    byte[] raw = offer.offer().encode();
                    out.writeShort(raw.length);
                    out.write(raw);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Empty for anything malformed; never throws. */
    static Optional<ChannelMessage> decode(byte[] data) {
        if (data == null || data.length > 8192) return Optional.empty();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) return Optional.empty();
            byte type = in.readByte();
            String name = in.readUTF();
            if (name.isBlank() || name.length() > 64) return Optional.empty();
            if (type == TYPE_IDENTITY) {
                String host = in.readUTF();
                int port = in.readInt();
                if (host.isBlank() || port <= 0 || port > 65535 || in.available() != 0) return Optional.empty();
                return Optional.of(new Identity(name, host, port));
            }
            if (type == TYPE_OFFER) {
                byte[] raw = new byte[in.readUnsignedShort()];
                in.readFully(raw);
                if (in.available() != 0) return Optional.empty();
                return Optional.of(new Offer(name, PairingOffer.decode(raw)));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
