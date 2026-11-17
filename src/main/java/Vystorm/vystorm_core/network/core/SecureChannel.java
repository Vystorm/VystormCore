package Vystorm.vystorm_core.network.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * An authenticated connection after {@link Handshake}. Every frame is sealed with AES-256-GCM; the 96-bit nonce is
 * the 4-byte direction id plus a 64-bit sequence number that both ends count implicitly. A dropped, replayed,
 * reordered or modified record therefore fails authentication and the channel is unusable afterwards.
 *
 * <p>{@link #send} is thread-safe; {@link #receive} must be called from one reader thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SecureChannel {
    static final int DIR_CLIENT_TO_HUB = 1;
    static final int DIR_HUB_TO_CLIENT = 2;
    private static final byte[] AAD = "vystorm-net-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BITS = 128;
    private static final int MAX_RECORD = FrameCodec.MAX_FRAME + 16;

    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;
    private final int sendDirection;
    private final int receiveDirection;
    private final String peerName;
    private long sendSequence;
    private long receiveSequence;
    private volatile boolean broken;

    SecureChannel(InputStream in, OutputStream out, byte[] sendKey, byte[] receiveKey,
                  int sendDirection, int receiveDirection, String peerName) {
        this.in = in instanceof DataInputStream d ? d : new DataInputStream(in);
        this.out = out instanceof DataOutputStream d ? d : new DataOutputStream(out);
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        this.sendDirection = sendDirection;
        this.receiveDirection = receiveDirection;
        this.peerName = peerName;
    }

    /** Server name of the other side (the hub reports {@code ""}). */
    public String peerName() { return peerName; }

    public synchronized void send(Frame frame) throws IOException {
        if (broken) throw new IOException("Channel closed after a failure");
        byte[] plain = FrameCodec.encode(frame);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_BITS, nonce(sendDirection, sendSequence)));
            cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(plain);
            sendSequence++;
            out.writeInt(sealed.length);
            out.write(sealed);
            out.flush();
        } catch (GeneralSecurityException failure) {
            broken = true;
            throw new IOException("Encryption failed", failure);
        } catch (IOException failure) {
            broken = true;
            throw failure;
        }
    }

    public Frame receive() throws IOException {
        if (broken) throw new IOException("Channel closed after a failure");
        try {
            byte[] sealed = readRecord(in, MAX_RECORD);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, new GCMParameterSpec(TAG_BITS, nonce(receiveDirection, receiveSequence)));
            cipher.updateAAD(AAD);
            byte[] plain = cipher.doFinal(sealed);
            receiveSequence++;
            return FrameCodec.decode(plain);
        } catch (GeneralSecurityException tampered) {
            broken = true;
            throw new FrameException("Record failed authentication (tampered, replayed or reordered)", tampered);
        } catch (IOException failure) {
            broken = true;
            throw failure;
        }
    }

    static byte[] nonce(int direction, long sequence) {
        return ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array();
    }

    static byte[] readRecord(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) throw new FrameException("Record length " + length + " out of range");
        byte[] record = new byte[length];
        in.readFully(record);
        return record;
    }
}
