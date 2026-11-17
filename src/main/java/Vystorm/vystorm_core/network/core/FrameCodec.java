package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Binary frame layout: {@code u8 type, str channel, str from, str to, u32 payloadLength, payload}; strings are
 * UTF-8 with a u16 length. Frames larger than {@link #MAX_FRAME} are rejected on both ends.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FrameCodec {
    /** Largest encoded frame (1 MiB). */
    public static final int MAX_FRAME = 1 << 20;
    private static final int MAX_STRING = 1024;

    private FrameCodec() {}

    public static byte[] encode(Frame frame) throws FrameException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + frame.payload().length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(frame.type().id());
            writeString(out, frame.channel());
            writeString(out, frame.from());
            writeString(out, frame.to());
            out.writeInt(frame.payload().length);
            out.write(frame.payload());
            out.flush();
            if (bytes.size() > MAX_FRAME) throw new FrameException("Frame too large: " + bytes.size() + " bytes");
            return bytes.toByteArray();
        } catch (FrameException failure) {
            throw failure;
        } catch (IOException impossible) {
            throw new FrameException("Encoding failed", impossible);
        }
    }

    public static Frame decode(byte[] data) throws FrameException {
        if (data == null) throw new FrameException("No data");
        if (data.length > MAX_FRAME) throw new FrameException("Frame too large: " + data.length + " bytes");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            FrameType type = FrameType.byId(in.readUnsignedByte());
            String channel = readString(in);
            String from = readString(in);
            String to = readString(in);
            int length = in.readInt();
            if (length < 0 || length > in.available()) throw new FrameException("Bad payload length " + length);
            byte[] payload = in.readNBytes(length);
            if (in.available() != 0) throw new FrameException("Trailing bytes after frame");
            return new Frame(type, channel, from, to, payload);
        } catch (EOFException truncated) {
            throw new FrameException("Truncated frame", truncated);
        } catch (FrameException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new FrameException("Decoding failed", failure);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_STRING) throw new FrameException("String field too long: " + raw.length + " bytes");
        out.writeShort(raw.length);
        out.write(raw);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING || length > in.available()) throw new FrameException("Bad string length " + length);
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
