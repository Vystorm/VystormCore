// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, UiMessage.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.*;

import Vystorm.vystorm_core.web.internal.clientproto.ui.ElementIds;
import java.util.ArrayList;
import java.util.List;

/**
 * Nachrichten auf {@code vystorm:ui}. Element-Inhalte sind JSON-Texte (Schema siehe {@code PROTOCOL.md} und
 * {@link Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema}); der Rahmen ist binär wie auf {@code vystorm:web}.
 */
public sealed interface UiMessage {

    int id();

    /** Element anlegen oder vollständig ersetzen. */
    record Put(String json) implements UiMessage {
        public Put {
            if (json == null || json.isEmpty()) throw new ProtocolException("json missing");
        }
        @Override public int id() { return UI_PUT; }
    }

    /** Oberste Felder eines vorhandenen Elements ersetzen (flaches Merge, danach vollständige Prüfung). */
    record Patch(String json) implements UiMessage {
        public Patch {
            if (json == null || json.isEmpty()) throw new ProtocolException("json missing");
        }
        @Override public int id() { return UI_PATCH; }
    }

    /** Elemente entfernen. */
    record Remove(List<String> ids) implements UiMessage {
        public Remove {
            if (ids == null || ids.isEmpty() || ids.size() > MAX_REMOVE_IDS) throw new ProtocolException("ids count invalid");
            for (String id : ids) if (!ElementIds.valid(id)) throw new ProtocolException("element id invalid");
            ids = List.copyOf(ids);
        }
        @Override public int id() { return UI_REMOVE; }
    }

    /** Alle Elemente mit diesem ID-Präfix entfernen (leer = alle). */
    record Clear(String prefix) implements UiMessage {
        public Clear {
            if (prefix == null || (!prefix.isEmpty() && !ElementIds.validPrefix(prefix))) throw new ProtocolException("prefix invalid");
        }
        @Override public int id() { return UI_CLEAR; }
    }

    /** Einmalige Benachrichtigung. */
    record Toast(String json) implements UiMessage {
        public Toast {
            if (json == null || json.isEmpty()) throw new ProtocolException("json missing");
        }
        @Override public int id() { return UI_TOAST; }
    }

    /** Client meldet ein abgelehntes Element (Hilfe für Plugin-Entwickler). */
    record Reject(String elementId, int code, String detail) implements UiMessage {
        public Reject {
            if (elementId == null || elementId.length() > MAX_ELEMENT_ID_BYTES) throw new ProtocolException("element id invalid");
            if (code < 1 || code > 255) throw new ProtocolException("code invalid");
            if (detail == null) throw new ProtocolException("detail missing");
        }
        @Override public int id() { return UI_REJECT; }
    }

    /**
     * Client meldet: der Spieler hat die Aktion eines sichtbaren Toasts ausgelöst (Client ≥ 0.9.0). {@code actionId} ist
     * die vom Server vergebene ID aus {@code action.id}; sie gibt nichts frei, der Server prüft alles selbst.
     */
    record Action(String actionId) implements UiMessage {
        public Action {
            if (actionId == null || !ElementIds.valid(actionId)) throw new ProtocolException("action id invalid");
        }
        @Override public int id() { return UI_ACTION; }
    }

    // ------------------------------------------------------------------ Codec

    static byte[] encode(UiMessage message) {
        Wire.Writer w = new Wire.Writer().writeVarInt(message.id());
        switch (message) {
            case Put m -> w.writeString("json", m.json(), MAX_ELEMENT_JSON_BYTES);
            case Patch m -> w.writeString("json", m.json(), MAX_PATCH_JSON_BYTES);
            case Remove m -> {
                w.writeVarInt(m.ids().size());
                for (String id : m.ids()) w.writeString("id", id, MAX_ELEMENT_ID_BYTES);
            }
            case Clear m -> w.writeString("prefix", m.prefix(), MAX_ELEMENT_ID_BYTES);
            case Toast m -> w.writeString("json", m.json(), MAX_TOAST_JSON_BYTES);
            case Reject m -> w.writeString("id", m.elementId(), MAX_ELEMENT_ID_BYTES).writeVarInt(m.code())
                    .writeString("detail", truncateUtf8(m.detail(), MAX_REJECT_DETAIL_BYTES), MAX_REJECT_DETAIL_BYTES);
            case Action m -> w.writeString("action", m.actionId(), MAX_ELEMENT_ID_BYTES);
        }
        int limit = message instanceof Reject || message instanceof Action ? MAX_SERVERBOUND_BYTES : MAX_UI_CLIENTBOUND_BYTES;
        if (w.size() > limit) throw new ProtocolException("message too large");
        return w.toByteArray();
    }

    /** Server→Client dekodieren; null bei unbekannter ID. */
    static UiMessage decodeClientbound(byte[] data) {
        if (data == null || data.length > MAX_UI_CLIENTBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        UiMessage out = switch (id) {
            case UI_PUT -> new Put(r.readString("json", MAX_ELEMENT_JSON_BYTES));
            case UI_PATCH -> new Patch(r.readString("json", MAX_PATCH_JSON_BYTES));
            case UI_REMOVE -> {
                int n = r.readVarInt("count", 1, MAX_REMOVE_IDS);
                List<String> ids = new ArrayList<>(n);
                for (int i = 0; i < n; i++) ids.add(r.readString("id", MAX_ELEMENT_ID_BYTES));
                yield new Remove(ids);
            }
            case UI_CLEAR -> new Clear(r.readString("prefix", MAX_ELEMENT_ID_BYTES));
            case UI_TOAST -> new Toast(r.readString("json", MAX_TOAST_JSON_BYTES));
            case UI_REJECT, UI_ACTION -> throw new ProtocolException("wrong direction");
            default -> null;
        };
        if (out != null) r.finish();
        return out;
    }

    /** Client→Server dekodieren (für Core); null bei unbekannter ID. */
    static UiMessage decodeServerbound(byte[] data) {
        if (data == null || data.length > MAX_SERVERBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        UiMessage out = switch (id) {
            case UI_REJECT -> new Reject(r.readString("id", MAX_ELEMENT_ID_BYTES), r.readVarInt("code", 1, 255),
                    r.readString("detail", MAX_REJECT_DETAIL_BYTES));
            case UI_ACTION -> new Action(r.readString("action", MAX_ELEMENT_ID_BYTES));
            case UI_PUT, UI_PATCH, UI_REMOVE, UI_CLEAR, UI_TOAST -> throw new ProtocolException("wrong direction");
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
