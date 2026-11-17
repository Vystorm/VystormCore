// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelMessage.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_HELLO;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_REQUEST;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_ACTION;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_CLOSED;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_BLOB_NEED;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_ERROR;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_WELCOME;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_OPEN;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_PATCH;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_RESULT;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_CLOSE;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_BEGIN;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_CHUNK;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.PANEL_DENIED;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.MAX_PANEL_SERVERBOUND_BYTES;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.MAX_PANEL_CLIENTBOUND_BYTES;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.DENY_WEB_OFF;
import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.DENY_OTHER;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import Vystorm.vystorm_core.web.internal.clientproto.Wire;
import java.util.Arrays;

/**
 * Nachrichten auf {@code vystorm:panel} (Spec §2.2). Rahmen und Grundtypen wie {@link Wire}: VarInt-ID + Felder, streng
 * (Grenzen, UTF-8, keine überzähligen Bytes, falsche Richtung = Fehler, unbekannte IDs = {@code null}).
 */
public sealed interface PanelMessage {
    int id();

    // ------------------------------------------------------------------ Client → Server

    record Hello(int schemaMin, int schemaMax, int features, int cacheKiB) implements PanelMessage {
        public Hello {
            if (schemaMin < 1 || schemaMax < schemaMin || schemaMax > 255) throw new ProtocolException("schema range invalid");
            if (features < 0 || features > 0xFFFF) throw new ProtocolException("features invalid");
            if (cacheKiB < 0 || cacheKiB > 1 << 20) throw new ProtocolException("cacheKiB invalid");
        }
        @Override public int id() { return PANEL_HELLO; }
    }

    /** {@code panelId} leer = Hotkey-Panel des Servers. {@code arg} ist Client-Eingabe (Ziel-Panel prüft selbst). */
    record Request(int requestId, String panelId, String arg) implements PanelMessage {
        public Request {
            if (requestId < 1) throw new ProtocolException("requestId invalid");
            if (panelId == null || (!panelId.isEmpty() && !PanelIds.panel(panelId))) throw new ProtocolException("panelId invalid");
            if (arg == null) throw new ProtocolException("arg missing");
        }
        @Override public int id() { return PANEL_REQUEST; }
    }

    record Action(int session, int seq, String elementId, int kind, String value) implements PanelMessage {
        public Action {
            if (session < 1 || seq < 1) throw new ProtocolException("session/seq invalid");
            if (!PanelIds.element(elementId)) throw new ProtocolException("elementId invalid");
            if (kind < KIND_CLICK || kind > KIND_SEARCH) throw new ProtocolException("kind invalid");
            if (value == null) throw new ProtocolException("value missing");
        }
        @Override public int id() { return PANEL_ACTION; }
    }

    record Closed(int session, int reason) implements PanelMessage {
        public Closed {
            if (session < 1) throw new ProtocolException("session invalid");
            if (reason < CLOSED_PLAYER || reason > CLOSED_SERVER) throw new ProtocolException("reason invalid");
        }
        @Override public int id() { return PANEL_CLOSED; }
    }

    record BlobNeed(String sha256hex) implements PanelMessage {
        public BlobNeed {
            if (!PanelIds.sha256Hex(sha256hex)) throw new ProtocolException("hash invalid");
        }
        @Override public int id() { return PANEL_BLOB_NEED; }
    }

    /** {@code elementId} leer = ganzes Dokument/Transfer. Detail wird beim Kodieren gekürzt. */
    record Error(int session, String elementId, int code, String detail) implements PanelMessage {
        public Error {
            if (session < 0) throw new ProtocolException("session invalid");
            if (elementId == null || (!elementId.isEmpty() && !PanelIds.element(elementId))) throw new ProtocolException("elementId invalid");
            if (code < 1 || code > 255) throw new ProtocolException("code invalid");
            if (detail == null) throw new ProtocolException("detail missing");
        }
        @Override public int id() { return PANEL_ERROR; }
    }

    // ------------------------------------------------------------------ Server → Client

    record Welcome(int schema, int features, String hotkeyPanel) implements PanelMessage {
        public Welcome {
            if (schema < 1 || schema > 255) throw new ProtocolException("schema invalid");
            if (features < 0 || features > 0xFFFF) throw new ProtocolException("features invalid");
            if (hotkeyPanel == null || (!hotkeyPanel.isEmpty() && !PanelIds.panel(hotkeyPanel))) throw new ProtocolException("hotkeyPanel invalid");
        }
        @Override public int id() { return PANEL_WELCOME; }
    }

    /** {@code transferId} 0 = {@code json} inline; sonst ist {@code json} leer und das Dokument kommt per BEGIN/CHUNK. */
    record Open(int session, int requestId, int flags, int transferId, String json) implements PanelMessage {
        public Open {
            if (session < 1 || requestId < 0 || transferId < 0) throw new ProtocolException("ids invalid");
            if ((flags & ~FLAGS_ALL) != 0) throw new ProtocolException("flags invalid");
            if (json == null || (transferId == 0) == json.isEmpty()) throw new ProtocolException("json xor transfer");
        }
        @Override public int id() { return PANEL_OPEN; }
    }

    record Patch(int session, int rev, int transferId, String json) implements PanelMessage {
        public Patch {
            if (session < 1 || rev < 1 || transferId < 0) throw new ProtocolException("ids invalid");
            if (json == null || (transferId == 0) == json.isEmpty()) throw new ProtocolException("json xor transfer");
        }
        @Override public int id() { return PANEL_PATCH; }
    }

    record Result(int session, int seq, int status, String json) implements PanelMessage {
        public Result {
            if (session < 1 || seq < 1) throw new ProtocolException("session/seq invalid");
            if (status < STATUS_OK || status > STATUS_RATE) throw new ProtocolException("status invalid");
            if (json == null) throw new ProtocolException("json missing");
        }
        @Override public int id() { return PANEL_RESULT; }
    }

    /** {@code session} 0 = alle. */
    record Close(int session, int reason) implements PanelMessage {
        public Close {
            if (session < 0) throw new ProtocolException("session invalid");
            if (reason < CLOSED_PLAYER || reason > CLOSED_SERVER) throw new ProtocolException("reason invalid");
        }
        @Override public int id() { return PANEL_CLOSE; }
    }

    record Begin(int transferId, int kind, int packedBytes, int rawBytes, byte[] sha256, int codec) implements PanelMessage {
        public Begin {
            if (transferId < 1) throw new ProtocolException("transferId invalid");
            if (kind < TRANSFER_DOCUMENT || kind > TRANSFER_BLOB) throw new ProtocolException("kind invalid");
            if (packedBytes < 1 || rawBytes < 1 || rawBytes > MAX_BLOB_BYTES || packedBytes > MAX_BLOB_BYTES) throw new ProtocolException("size invalid");
            if (sha256 == null || sha256.length != 32) throw new ProtocolException("sha256 invalid");
            if (codec != CODEC_RAW && codec != CODEC_DEFLATE) throw new ProtocolException("codec invalid");
            sha256 = sha256.clone();
        }
        @Override public byte[] sha256() { return sha256.clone(); }
        @Override public int id() { return PANEL_BEGIN; }
        @Override public boolean equals(Object o) {
            return o instanceof Begin b && b.transferId == transferId && b.kind == kind && b.packedBytes == packedBytes
                    && b.rawBytes == rawBytes && b.codec == codec && Arrays.equals(b.sha256, sha256);
        }
        @Override public int hashCode() { return 31 * transferId + Arrays.hashCode(sha256); }
        @Override public String toString() { return "Begin[" + transferId + ", kind=" + kind + ", " + packedBytes + "/" + rawBytes + "]"; }
    }

    record Chunk(int transferId, int index, byte[] data) implements PanelMessage {
        public Chunk {
            if (transferId < 1 || index < 0) throw new ProtocolException("ids invalid");
            if (data == null || data.length < 1 || data.length > MAX_CHUNK_BYTES) throw new ProtocolException("chunk size invalid");
        }
        @Override public int id() { return PANEL_CHUNK; }
        @Override public boolean equals(Object o) {
            return o instanceof Chunk c && c.transferId == transferId && c.index == index && Arrays.equals(c.data, data);
        }
        @Override public int hashCode() { return 31 * (31 * transferId + index) + Arrays.hashCode(data); }
        @Override public String toString() { return "Chunk[" + transferId + "#" + index + ", " + data.length + " B]"; }
    }

    record Denied(int requestId, int reason, String message) implements PanelMessage {
        public Denied {
            if (requestId < 0) throw new ProtocolException("requestId invalid");
            if (reason < DENY_WEB_OFF || reason > DENY_OTHER) throw new ProtocolException("reason invalid");
            if (message == null) throw new ProtocolException("message missing");
        }
        @Override public int id() { return PANEL_DENIED; }
    }

    // ------------------------------------------------------------------ Codec

    static byte[] encode(PanelMessage message) {
        Wire.Writer w = new Wire.Writer().writeVarInt(message.id());
        switch (message) {
            case Hello m -> w.writeVarInt(m.schemaMin()).writeVarInt(m.schemaMax()).writeVarInt(m.features()).writeVarInt(m.cacheKiB());
            case Request m -> w.writeVarInt(m.requestId()).writeString("panelId", m.panelId(), MAX_PANEL_ID_BYTES)
                    .writeString("arg", m.arg(), MAX_ARG_BYTES);
            case Action m -> w.writeVarInt(m.session()).writeVarInt(m.seq()).writeString("elementId", m.elementId(), MAX_ELEMENT_ID_BYTES)
                    .writeVarInt(m.kind()).writeString("value", m.value(), MAX_ACTION_VALUE_BYTES);
            case Closed m -> w.writeVarInt(m.session()).writeVarInt(m.reason());
            case BlobNeed m -> w.writeString("hash", m.sha256hex(), 64);
            case Error m -> w.writeVarInt(m.session()).writeString("elementId", m.elementId(), MAX_ELEMENT_ID_BYTES).writeVarInt(m.code())
                    .writeString("detail", truncateUtf8(m.detail(), MAX_ERROR_DETAIL_BYTES), MAX_ERROR_DETAIL_BYTES);
            case Welcome m -> w.writeVarInt(m.schema()).writeVarInt(m.features()).writeString("hotkeyPanel", m.hotkeyPanel(), MAX_PANEL_ID_BYTES);
            case Open m -> w.writeVarInt(m.session()).writeVarInt(m.requestId()).writeVarInt(m.flags()).writeVarInt(m.transferId())
                    .writeString("json", m.json(), MAX_INLINE_JSON_BYTES);
            case Patch m -> w.writeVarInt(m.session()).writeVarInt(m.rev()).writeVarInt(m.transferId()).writeString("json", m.json(), MAX_INLINE_JSON_BYTES);
            case Result m -> w.writeVarInt(m.session()).writeVarInt(m.seq()).writeVarInt(m.status()).writeString("json", m.json(), MAX_RESULT_JSON_BYTES);
            case Close m -> w.writeVarInt(m.session()).writeVarInt(m.reason());
            case Begin m -> w.writeVarInt(m.transferId()).writeVarInt(m.kind()).writeVarInt(m.packedBytes()).writeVarInt(m.rawBytes())
                    .writeBytes(m.sha256()).writeVarInt(m.codec());
            case Chunk m -> w.writeVarInt(m.transferId()).writeVarInt(m.index()).writeVarInt(m.data().length).writeBytes(m.data());
            case Denied m -> w.writeVarInt(m.requestId()).writeVarInt(m.reason()).writeString("message", m.message(), MAX_DENIED_MESSAGE_BYTES);
        }
        int limit = serverbound(message) ? MAX_PANEL_SERVERBOUND_BYTES : MAX_PANEL_CLIENTBOUND_BYTES;
        if (w.size() > limit) throw new ProtocolException("message too large");
        return w.toByteArray();
    }

    private static boolean serverbound(PanelMessage m) {
        return m instanceof Hello || m instanceof Request || m instanceof Action || m instanceof Closed || m instanceof BlobNeed || m instanceof Error;
    }

    /** Server → Client (im Client); null bei unbekannter ID. */
    static PanelMessage decodeClientbound(byte[] data) {
        if (data == null || data.length > MAX_PANEL_CLIENTBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        PanelMessage out = switch (id) {
            case PANEL_WELCOME -> new Welcome(r.readVarInt(), r.readVarInt(), r.readString("hotkeyPanel", MAX_PANEL_ID_BYTES));
            case PANEL_OPEN -> new Open(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readString("json", MAX_INLINE_JSON_BYTES));
            case PANEL_PATCH -> new Patch(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readString("json", MAX_INLINE_JSON_BYTES));
            case PANEL_RESULT -> new Result(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readString("json", MAX_RESULT_JSON_BYTES));
            case PANEL_CLOSE -> new Close(r.readVarInt(), r.readVarInt());
            case PANEL_BEGIN -> new Begin(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readBytes("sha256", 32), r.readVarInt());
            case PANEL_CHUNK -> {
                int transfer = r.readVarInt();
                int index = r.readVarInt();
                int n = r.readVarInt("n", 1, MAX_CHUNK_BYTES);
                yield new Chunk(transfer, index, r.readBytes("data", n));
            }
            case PANEL_DENIED -> new Denied(r.readVarInt(), r.readVarInt(), r.readString("message", MAX_DENIED_MESSAGE_BYTES));
            case PANEL_HELLO, PANEL_REQUEST, PANEL_ACTION, PANEL_CLOSED, PANEL_BLOB_NEED, PANEL_ERROR -> throw new ProtocolException("wrong direction");
            default -> null;
        };
        if (out != null) r.finish();
        return out;
    }

    /** Client → Server (für Core); null bei unbekannter ID. */
    static PanelMessage decodeServerbound(byte[] data) {
        if (data == null || data.length > MAX_PANEL_SERVERBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        PanelMessage out = switch (id) {
            case PANEL_HELLO -> new Hello(r.readVarInt(), r.readVarInt(), r.readVarInt(), r.readVarInt());
            case PANEL_REQUEST -> new Request(r.readVarInt(), r.readString("panelId", MAX_PANEL_ID_BYTES), r.readString("arg", MAX_ARG_BYTES));
            case PANEL_ACTION -> new Action(r.readVarInt(), r.readVarInt(), r.readString("elementId", MAX_ELEMENT_ID_BYTES), r.readVarInt(),
                    r.readString("value", MAX_ACTION_VALUE_BYTES));
            case PANEL_CLOSED -> new Closed(r.readVarInt(), r.readVarInt());
            case PANEL_BLOB_NEED -> new BlobNeed(r.readString("hash", 64));
            case PANEL_ERROR -> new Error(r.readVarInt(), r.readString("elementId", MAX_ELEMENT_ID_BYTES), r.readVarInt(),
                    r.readString("detail", MAX_ERROR_DETAIL_BYTES));
            case PANEL_WELCOME, PANEL_OPEN, PANEL_PATCH, PANEL_RESULT, PANEL_CLOSE, PANEL_BEGIN, PANEL_CHUNK, PANEL_DENIED -> throw new ProtocolException("wrong direction");
            default -> null;
        };
        if (out != null) r.finish();
        return out;
    }

    /** Kürzt auf höchstens {@code maxBytes} UTF-8-Bytes, ohne ein Zeichen zu zerschneiden. */
    static String truncateUtf8(String s, int maxBytes) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) break;
            out.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
