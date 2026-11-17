// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, WebMessage.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import static Vystorm.vystorm_core.web.internal.clientproto.Protocol.*;

/**
 * Nachrichten auf {@code vystorm:web}. Jede Nachricht beginnt mit ihrer ID als VarInt; Felder siehe
 * {@code PROTOCOL.md}. Dekodiert wird richtungsabhängig: ein Client nimmt nur Server→Client-Nachrichten an und
 * umgekehrt.
 */
public sealed interface WebMessage {

    int id();

    // ------------------------------------------------------------------ Client → Server

    /** Client meldet sich an: unterstützte Protokollversionen, Mod-Version, Loader, Fähigkeiten. */
    record Hello(int minVersion, int maxVersion, String modVersion, String loader, int capabilities) implements WebMessage {
        public Hello {
            if (minVersion < 1 || maxVersion < minVersion || maxVersion > 255) throw new ProtocolException("hello version range invalid");
            if (capabilities < 0) throw new ProtocolException("capabilities invalid");
        }
        @Override public int id() { return WEB_HELLO; }
    }

    /** Client bittet um ein Login-Token für ein Modul (leer = Übersicht). */
    record OpenRequest(int requestId, String module, int target) implements WebMessage {
        public OpenRequest {
            if (requestId < 1) throw new ProtocolException("requestId invalid");
            ModuleIds.check("module", module);
            if (target != TARGET_OVERLAY && target != TARGET_EXTERNAL && target != TARGET_PRELOAD) throw new ProtocolException("target invalid");
        }
        @Override public int id() { return WEB_OPEN_REQUEST; }
    }

    /** Anzeige im Spiel wurde geschlossen (bis 0.6.0 das Overlay). */
    record Closed(String module, int reason) implements WebMessage {
        public Closed {
            ModuleIds.check("module", module);
            if (reason < 0 || reason > 15) throw new ProtocolException("reason invalid");
        }
        @Override public int id() { return WEB_CLOSED; }
    }

    /** Sichtbarkeits-Zustand des Clients (Bits {@code STATE_*}). */
    record ClientState(int flags) implements WebMessage {
        public ClientState {
            if (flags < 0 || flags > 0xFFFF) throw new ProtocolException("flags invalid");
        }
        @Override public int id() { return WEB_CLIENT_STATE; }
    }

    // ------------------------------------------------------------------ Server → Client

    /**
     * Antwort auf HELLO.
     *
     * @param version       gewählte Protokollversion (im Bereich des HELLO)
     * @param webBase       öffentliche Basis-URL der Weboberfläche; leer = Web aus
     * @param hotkeyModule  Modul, das die Taste K öffnet (leer = Übersicht)
     * @param serverFlags   {@code SERVER_*}
     */
    record Welcome(int version, String webBase, String hotkeyModule, int serverFlags) implements WebMessage {
        public Welcome {
            if (version < 1 || version > 255) throw new ProtocolException("version invalid");
            if (webBase == null) throw new ProtocolException("webBase missing");
            ModuleIds.check("hotkeyModule", hotkeyModule);
            if (serverFlags < 0) throw new ProtocolException("serverFlags invalid");
        }
        @Override public int id() { return WEB_WELCOME; }
    }

    /**
     * Einmal-Token zum Öffnen eines Moduls. {@code requestId} 0 = vom Server angestoßen.
     * {@link #toString()} verbirgt das Token.
     */
    record Open(int requestId, String module, String token, int ttlSeconds, String title) implements WebMessage {
        public Open {
            if (requestId < 0) throw new ProtocolException("requestId invalid");
            ModuleIds.check("module", module);
            if (!LoginToken.wellFormed(token)) throw new ProtocolException("token malformed");
            if (ttlSeconds < 1 || ttlSeconds > LoginToken.MAX_TTL_SECONDS) throw new ProtocolException("ttl invalid");
            if (title == null) throw new ProtocolException("title missing");
        }
        @Override public int id() { return WEB_OPEN; }
        @Override public String toString() {
            return "Open[requestId=" + requestId + ", module=" + module + ", token=<redacted>, ttlSeconds=" + ttlSeconds + ", title=" + title + "]";
        }
    }

    /** Anfrage abgelehnt; {@code message} ist Klartext für den Spieler. */
    record Denied(int requestId, int reason, String message) implements WebMessage {
        public Denied {
            if (requestId < 0) throw new ProtocolException("requestId invalid");
            if (reason < 1 || reason > 255) throw new ProtocolException("reason invalid");
            if (message == null) throw new ProtocolException("message missing");
        }
        @Override public int id() { return WEB_DENIED; }
    }

    /**
     * Server möchte ein Modul zeigen (z. B. statt eines Kistenmenüs). {@code page} ist die relative Seite auf dem
     * Web-Ursprung ({@code /app/skills#node/12}, {@code /m/shop/}); leer = Seite, auf der der Login landet. Ist der
     * vorgewärmte Browser angemeldet, zeigt der Client ihn sofort (und lädt {@code page}); sonst fordert er selbst ein
     * Token für {@code module} an und steuert {@code page} nach dem Login an.
     */
    record Show(String module, String page) implements WebMessage {
        public Show {
            ModuleIds.check("module", module);
            ModuleIds.checkPage("page", page);
        }
        @Override public int id() { return WEB_SHOW; }
    }

    /** Server schließt die Anzeige im Spiel (leer = egal welches Modul). */
    record Close(String module) implements WebMessage {
        public Close {
            ModuleIds.check("module", module);
        }
        @Override public int id() { return WEB_CLOSE; }
    }

    // ------------------------------------------------------------------ Codec

    static byte[] encode(WebMessage message) {
        Wire.Writer w = new Wire.Writer().writeVarInt(message.id());
        switch (message) {
            case Hello m -> w.writeVarInt(m.minVersion()).writeVarInt(m.maxVersion())
                    .writeString("modVersion", m.modVersion(), MAX_VERSION_BYTES)
                    .writeString("loader", m.loader(), MAX_LOADER_BYTES)
                    .writeVarInt(m.capabilities());
            case OpenRequest m -> w.writeVarInt(m.requestId()).writeString("module", m.module(), MAX_MODULE_ID_BYTES).writeVarInt(m.target());
            case Closed m -> w.writeString("module", m.module(), MAX_MODULE_ID_BYTES).writeVarInt(m.reason());
            case ClientState m -> w.writeVarInt(m.flags());
            case Welcome m -> w.writeVarInt(m.version()).writeString("webBase", m.webBase(), MAX_BASE_URL_BYTES)
                    .writeString("hotkeyModule", m.hotkeyModule(), MAX_MODULE_ID_BYTES).writeVarInt(m.serverFlags());
            case Open m -> w.writeVarInt(m.requestId()).writeString("module", m.module(), MAX_MODULE_ID_BYTES)
                    .writeString("token", m.token(), LoginToken.LENGTH).writeVarInt(m.ttlSeconds())
                    .writeString("title", m.title(), MAX_TITLE_BYTES);
            case Denied m -> w.writeVarInt(m.requestId()).writeVarInt(m.reason()).writeString("message", m.message(), MAX_MESSAGE_BYTES);
            case Close m -> w.writeString("module", m.module(), MAX_MODULE_ID_BYTES);
            case Show m -> w.writeString("module", m.module(), MAX_MODULE_ID_BYTES).writeString("page", m.page(), MAX_PAGE_BYTES);
        }
        int limit = serverbound(message) ? MAX_SERVERBOUND_BYTES : MAX_WEB_CLIENTBOUND_BYTES;
        if (w.size() > limit) throw new ProtocolException("message too large");
        return w.toByteArray();
    }

    /** Ob die Nachricht vom Client zum Server geht. */
    static boolean serverbound(WebMessage message) {
        return message instanceof Hello || message instanceof OpenRequest || message instanceof Closed || message instanceof ClientState;
    }

    /**
     * Dekodiert eine Server→Client-Nachricht (für den Client).
     *
     * @return null bei unbekannter ID (Vorwärtskompatibilität: ignorieren)
     * @throws ProtocolException bei kaputten oder zu großen Daten
     */
    static WebMessage decodeClientbound(byte[] data) {
        if (data == null || data.length > MAX_WEB_CLIENTBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        WebMessage out = switch (id) {
            case WEB_WELCOME -> new Welcome(r.readVarInt("version", 1, 255), r.readString("webBase", MAX_BASE_URL_BYTES),
                    r.readString("hotkeyModule", MAX_MODULE_ID_BYTES), r.readVarInt("serverFlags", 0, Integer.MAX_VALUE));
            case WEB_OPEN -> new Open(r.readVarInt("requestId", 0, Integer.MAX_VALUE), r.readString("module", MAX_MODULE_ID_BYTES),
                    r.readString("token", LoginToken.LENGTH), r.readVarInt("ttl", 1, LoginToken.MAX_TTL_SECONDS),
                    r.readString("title", MAX_TITLE_BYTES));
            case WEB_DENIED -> new Denied(r.readVarInt("requestId", 0, Integer.MAX_VALUE), r.readVarInt("reason", 1, 255),
                    r.readString("message", MAX_MESSAGE_BYTES));
            case WEB_CLOSE -> new Close(r.readString("module", MAX_MODULE_ID_BYTES));
            case WEB_SHOW -> new Show(r.readString("module", MAX_MODULE_ID_BYTES), r.readString("page", MAX_PAGE_BYTES));
            case WEB_HELLO, WEB_OPEN_REQUEST, WEB_CLOSED, WEB_CLIENT_STATE -> throw new ProtocolException("wrong direction");
            default -> null;
        };
        if (out != null) r.finish();
        return out;
    }

    /**
     * Dekodiert eine Client→Server-Nachricht (für Core).
     *
     * @return null bei unbekannter ID
     * @throws ProtocolException bei kaputten oder zu großen Daten
     */
    static WebMessage decodeServerbound(byte[] data) {
        if (data == null || data.length > MAX_SERVERBOUND_BYTES) throw new ProtocolException("message too large");
        Wire.Reader r = new Wire.Reader(data);
        int id = r.readVarInt();
        WebMessage out = switch (id) {
            case WEB_HELLO -> new Hello(r.readVarInt("minVersion", 1, 255), r.readVarInt("maxVersion", 1, 255),
                    r.readString("modVersion", MAX_VERSION_BYTES), r.readString("loader", MAX_LOADER_BYTES),
                    r.readVarInt("capabilities", 0, Integer.MAX_VALUE));
            case WEB_OPEN_REQUEST -> new OpenRequest(r.readVarInt("requestId", 1, Integer.MAX_VALUE), r.readString("module", MAX_MODULE_ID_BYTES),
                    r.readVarInt("target", 0, 2));
            case WEB_CLOSED -> new Closed(r.readString("module", MAX_MODULE_ID_BYTES), r.readVarInt("reason", 0, 15));
            case WEB_CLIENT_STATE -> new ClientState(r.readVarInt("flags", 0, 0xFFFF));
            case WEB_WELCOME, WEB_OPEN, WEB_DENIED, WEB_CLOSE, WEB_SHOW -> throw new ProtocolException("wrong direction");
            default -> null;
        };
        if (out != null) r.finish();
        return out;
    }
}
