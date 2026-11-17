// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Wire.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Binäre Grundtypen der Vystorm-Kanäle, kompatibel zu Minecrafts {@code FriendlyByteBuf}: VarInt (LEB128, höchstens
 * 5 Bytes), Bool (ein Byte 0/1), String (VarInt-Bytelänge + UTF-8), Long (8 Bytes Big Endian).
 *
 * <p>Streng: zu lange Felder, abgeschnittene Daten, ungültiges UTF-8, falsche Bool-Werte und überzählige Bytes am
 * Ende sind Fehler ({@link ProtocolException}). Reines Java ohne Abhängigkeiten, damit Core (Paper) denselben Code
 * benutzen kann.
 */
public final class Wire {
    private Wire() {}

    /** Liest eine Nachricht; {@link #finish()} prüft, dass nichts übrig bleibt. */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            if (data == null) throw new ProtocolException("no data");
            this.data = data;
        }

        public int remaining() { return data.length - pos; }

        public int readByte() {
            if (pos >= data.length) throw new ProtocolException("truncated");
            return data[pos++] & 0xFF;
        }

        public int readVarInt() {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = readByte();
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    if (i == 4 && (b & 0x70) != 0) throw new ProtocolException("varint overflow");
                    return value;
                }
            }
            throw new ProtocolException("varint too long");
        }

        /** VarInt im Bereich {@code [min, max]}. */
        public int readVarInt(String field, int min, int max) {
            int v = readVarInt();
            if (v < min || v > max) throw new ProtocolException(field + " out of range");
            return v;
        }

        public boolean readBool(String field) {
            int b = readByte();
            if (b > 1) throw new ProtocolException(field + " is not a boolean");
            return b == 1;
        }

        public long readLong() {
            if (remaining() < 8) throw new ProtocolException("truncated");
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[pos++] & 0xFFL);
            return v;
        }

        /** UTF-8-String mit höchstens {@code maxBytes} Bytes (streng dekodiert). */
        public String readString(String field, int maxBytes) {
            int len = readVarInt();
            if (len < 0 || len > maxBytes) throw new ProtocolException(field + " too long");
            if (remaining() < len) throw new ProtocolException("truncated");
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                CharBuffer chars = decoder.decode(ByteBuffer.wrap(data, pos, len));
                pos += len;
                return chars.toString();
            } catch (CharacterCodingException bad) {
                throw new ProtocolException(field + " is not valid UTF-8");
            }
        }

        /** Genau {@code n} rohe Bytes. */
        public byte[] readBytes(String field, int n) {
            if (n < 0 || remaining() < n) throw new ProtocolException(field + " truncated");
            byte[] out = java.util.Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return out;
        }

        /** Keine überzähligen Bytes. */
        public void finish() {
            if (pos != data.length) throw new ProtocolException("trailing bytes");
        }
    }

    /** Schreibt eine Nachricht; prüft dieselben Grenzen wie der {@link Reader}. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        public Writer writeByte(int b) { out.write(b & 0xFF); return this; }

        public Writer writeVarInt(int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
            return this;
        }

        public Writer writeBool(boolean b) { return writeByte(b ? 1 : 0); }

        public Writer writeLong(long v) {
            for (int i = 7; i >= 0; i--) out.write((int) (v >>> (8 * i)) & 0xFF);
            return this;
        }

        public Writer writeString(String field, String value, int maxBytes) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) throw new ProtocolException(field + " too long");
            writeVarInt(bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }

        public Writer writeBytes(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
            return this;
        }

        public int size() { return out.size(); }

        public byte[] toByteArray() { return out.toByteArray(); }
    }

    /** Bytes, die {@link Writer#writeVarInt} für {@code value} braucht. */
    public static int varIntSize(int value) {
        int size = 1;
        int v = value;
        while ((v & ~0x7F) != 0) { size++; v >>>= 7; }
        return size;
    }
}
