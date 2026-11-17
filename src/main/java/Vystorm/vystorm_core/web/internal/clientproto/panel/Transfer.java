// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Transfer.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Große Dokumente, Patches und Blobs als P_BEGIN + P_CHUNK (Spec §2.3). SHA-256 über die **rohen** Bytes, geprüft nach
 * dem Entpacken; Entpacken hart begrenzt auf {@code rawBytes} (≤ Art-Grenze) und Verhältnis ≤ 1:64 (Zip-Bomben).
 */
public final class Transfer {
    private Transfer() {}

    public record Completed(PanelMessage.Begin begin, byte[] packed) {}

    /** Roh-JSON bis 8 KiB inline, darüber Transfer (Plan-Entscheidung 1). */
    public static boolean inline(int rawBytes) { return rawBytes <= INLINE_MAX_BYTES; }

    public static int maxRaw(int kind) {
        return switch (kind) {
            case TRANSFER_DOCUMENT -> MAX_DOCUMENT_BYTES;
            case TRANSFER_PATCH -> MAX_PATCH_BYTES;
            case TRANSFER_BLOB -> MAX_BLOB_BYTES;
            default -> throw new ProtocolException("kind invalid");
        };
    }

    /** Größte gepackte Größe einer Art: Rohgrenze + Deflate-Aufschlag für unkomprimierbare Daten (≤ 0,1 % + 64 B). */
    public static int maxPacked(int kind) {
        int raw = maxRaw(kind);
        return raw + raw / 1024 + 64;
    }

    public static byte[] sha256(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    /** Server-Seite: BEGIN + CHUNKs, immer Deflate. */
    public static List<PanelMessage> split(int transferId, int kind, byte[] raw) {
        if (raw.length < 1 || raw.length > maxRaw(kind)) throw new ProtocolException("raw size invalid");
        byte[] packed = deflate(raw);
        List<PanelMessage> out = new ArrayList<>(2 + packed.length / MAX_CHUNK_BYTES);
        out.add(new PanelMessage.Begin(transferId, kind, packed.length, raw.length, sha256(raw), CODEC_DEFLATE));
        for (int i = 0, index = 0; i < packed.length; i += MAX_CHUNK_BYTES, index++)
            out.add(new PanelMessage.Chunk(transferId, index, java.util.Arrays.copyOfRange(packed, i, Math.min(packed.length, i + MAX_CHUNK_BYTES))));
        return out;
    }

    /** Client-Seite (Worker-Thread): entpacken mit Obergrenze, Hash prüfen. */
    public static byte[] unpack(PanelMessage.Begin begin, byte[] packed, Deadline deadline) {
        if (packed.length != begin.packedBytes()) throw new ProtocolException("packed size mismatch");
        byte[] raw;
        if (begin.codec() == CODEC_RAW) {
            raw = packed;
        } else {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(packed);
                raw = new byte[begin.rawBytes()];
                int n = 0;
                while (n < raw.length) {
                    deadline.check();
                    int got = inflater.inflate(raw, n, Math.min(64 * 1024, raw.length - n));
                    if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                    n += got;
                }
                if (n != raw.length) throw new ProtocolException("raw size mismatch");
                // Mehr Daten als angekündigt = Bombe oder Lüge.
                if (!inflater.finished()) {
                    byte[] probe = new byte[1];
                    if (inflater.inflate(probe) > 0 || !inflater.finished()) throw new ProtocolException("inflated beyond rawBytes");
                }
            } catch (DataFormatException bad) {
                throw new ProtocolException("deflate data invalid");
            } finally {
                inflater.end();
            }
        }
        deadline.check();
        if (!MessageDigest.isEqual(sha256(raw), begin.sha256())) throw new ProtocolException("sha256 mismatch");
        return raw;
    }

    static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(6);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buf = new byte[16 * 1024];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** Setzt eingehende Transfers zusammen (nicht threadsicher; Client-Thread). */
    public static final class Assembler {
        private record Pending(PanelMessage.Begin begin, byte[] buffer, long startedAt, int[] state) {}   // state[0]=nextIndex, state[1]=received

        private final LongSupplier millis;
        private final Map<Integer, Pending> pending = new HashMap<>();

        public Assembler(LongSupplier millis) { this.millis = millis; }

        public void begin(PanelMessage.Begin b) {
            expire();
            if (pending.containsKey(b.transferId())) throw new ProtocolException("transfer id in use");
            if (pending.size() >= MAX_TRANSFERS) throw new ProtocolException("too many transfers");
            if (b.rawBytes() > maxRaw(b.kind())) throw new ProtocolException("raw size over limit");
            if (b.packedBytes() > maxPacked(b.kind())) throw new ProtocolException("packed size over limit");
            if (b.codec() == CODEC_RAW && b.rawBytes() != b.packedBytes()) throw new ProtocolException("raw codec size mismatch");
            if ((long) b.rawBytes() > (long) b.packedBytes() * MAX_INFLATE_RATIO) throw new ProtocolException("ratio over limit");
            pending.put(b.transferId(), new Pending(b, new byte[b.packedBytes()], millis.getAsLong(), new int[2]));
        }

        /** @return den fertigen Transfer oder null, solange Chunks fehlen */
        public Completed chunk(PanelMessage.Chunk c) {
            Pending p = pending.get(c.transferId());
            if (p == null) throw new ProtocolException("unknown transfer");
            if (c.index() != p.state[0] || p.state[1] + c.data().length > p.buffer.length) {
                pending.remove(c.transferId());
                throw new ProtocolException("chunk out of order or too large");
            }
            System.arraycopy(c.data(), 0, p.buffer, p.state[1], c.data().length);
            p.state[0]++;
            p.state[1] += c.data().length;
            if (p.state[1] < p.buffer.length) return null;
            pending.remove(c.transferId());
            return new Completed(p.begin, p.buffer);
        }

        /** Unvollständige Transfers nach 10 s verwerfen. */
        public void expire() {
            long now = millis.getAsLong();
            pending.values().removeIf(p -> now - p.startedAt > TRANSFER_TIMEOUT_MS);
        }

        public void discard(int transferId) { pending.remove(transferId); }

        public void clear() { pending.clear(); }

        public int active() { return pending.size(); }
    }
}
