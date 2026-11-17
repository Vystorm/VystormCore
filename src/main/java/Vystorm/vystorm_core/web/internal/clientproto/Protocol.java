// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Protocol.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

/**
 * Konstanten des Vystorm-Client-Protokolls (siehe {@code PROTOCOL.md}). Änderungen hier sind Protokolländerungen:
 * {@link #VERSION} erhöhen, alte Versionen weiter lesen können.
 */
public final class Protocol {
    private Protocol() {}

    /** Kanal für Handshake und Web-Seiten (Tokens, Öffnen/Schließen, Client-Zustand). */
    public static final String CHANNEL_WEB = "vystorm:web";
    /** Kanal für native Elemente (HUD, Toasts, Marker, Tooltips). */
    public static final String CHANNEL_UI = "vystorm:ui";

    /**
     * Höchste Protokollversion dieses Codes. Version 2 (Client 0.4.0, Core 0.21.0): CLIENT_STATE trägt
     * {@link #STATE_ORIGIN_TRUSTED} und wird direkt nach dem WELCOME geschickt. Sonst unverändert; Version-1-Gegenstellen
     * funktionieren weiter (der Server wählt die gemeinsame Version).
     */
    public static final int VERSION = 2;
    /** Erste Version mit {@link #STATE_ORIGIN_TRUSTED}. */
    public static final int VERSION_TRUST_STATE = 2;
    /** Niedrigste noch unterstützte Protokollversion. */
    public static final int MIN_VERSION = 1;

    // ------------------------------------------------------------------ Größen (Bytes)

    /** Höchstgröße einer Nachricht Client → Server (beide Kanäle). */
    public static final int MAX_SERVERBOUND_BYTES = 1024;
    /** Höchstgröße einer Nachricht Server → Client auf {@code vystorm:web}. */
    public static final int MAX_WEB_CLIENTBOUND_BYTES = 4096;
    /** Höchstgröße einer Nachricht Server → Client auf {@code vystorm:ui}. */
    public static final int MAX_UI_CLIENTBOUND_BYTES = 32 * 1024;

    public static final int MAX_MODULE_ID_BYTES = 32;
    /** Relative Seite auf dem Web-Ursprung (Pfad + optional Fragment). */
    public static final int MAX_PAGE_BYTES = 288;
    public static final int MAX_VERSION_BYTES = 32;
    public static final int MAX_LOADER_BYTES = 16;
    public static final int MAX_BASE_URL_BYTES = 256;
    public static final int MAX_TITLE_BYTES = 128;
    public static final int MAX_MESSAGE_BYTES = 512;
    public static final int MAX_ELEMENT_JSON_BYTES = 16 * 1024;
    public static final int MAX_PATCH_JSON_BYTES = 4096;
    public static final int MAX_TOAST_JSON_BYTES = 4096;
    public static final int MAX_ELEMENT_ID_BYTES = 64;
    public static final int MAX_REMOVE_IDS = 64;
    public static final int MAX_REJECT_DETAIL_BYTES = 128;

    // ------------------------------------------------------------------ Nachrichten-IDs

    /** vystorm:web, Client → Server. */
    public static final int WEB_HELLO = 0x01;
    /** vystorm:web, Server → Client. */
    public static final int WEB_WELCOME = 0x02;
    /** vystorm:web, Client → Server. */
    public static final int WEB_OPEN_REQUEST = 0x03;
    /** vystorm:web, Server → Client. */
    public static final int WEB_OPEN = 0x04;
    /** vystorm:web, Server → Client. */
    public static final int WEB_DENIED = 0x05;
    /** vystorm:web, Server → Client. */
    public static final int WEB_CLOSE = 0x06;
    /** vystorm:web, Client → Server. */
    public static final int WEB_CLOSED = 0x07;
    /** vystorm:web, Client → Server. */
    public static final int WEB_CLIENT_STATE = 0x08;
    /** vystorm:web, Server → Client. */
    public static final int WEB_SHOW = 0x09;

    /** vystorm:ui, Server → Client. */
    public static final int UI_PUT = 0x10;
    /** vystorm:ui, Server → Client. */
    public static final int UI_PATCH = 0x11;
    /** vystorm:ui, Server → Client. */
    public static final int UI_REMOVE = 0x12;
    /** vystorm:ui, Server → Client. */
    public static final int UI_CLEAR = 0x13;
    /** vystorm:ui, Server → Client. */
    public static final int UI_TOAST = 0x14;
    /** vystorm:ui, Client → Server. */
    public static final int UI_REJECT = 0x1F;
    /**
     * vystorm:ui, Client → Server: der Spieler hat die Aktion eines sichtbaren Toasts ausgelöst (Taste „Benachrichtigung
     * annehmen“). Trägt nur die vom Server vergebene Aktions-ID; der Server prüft alles selbst. @since Client 0.9.0
     */
    public static final int UI_ACTION = 0x1E;

    // ------------------------------------------------------------------ Fähigkeiten (HELLO)

    /**
     * Seiten im Spiel anzeigen (bis 0.6.0 das eingebettete Browser-Overlay). Client ≥ 0.7.0 setzt das Bit nicht (Seiten
     * öffnen im Systembrowser); nicht neu belegen. Core schickt SHOW nur mit diesem Bit.
     */
    public static final int CAP_BROWSER_OVERLAY = 1;
    /** Login-Link im externen Browser öffnen (immer vorhanden). */
    public static final int CAP_EXTERNAL_BROWSER = 1 << 1;
    /** HUD-Elemente: panel, bar, slots. */
    public static final int CAP_HUD = 1 << 2;
    /** Toasts. */
    public static final int CAP_TOASTS = 1 << 3;
    /** Weltmarker. */
    public static final int CAP_MARKERS = 1 << 4;
    /** Tooltip-Erweiterungen für Items. */
    public static final int CAP_TOOLTIPS = 1 << 5;
    /** JS-Brücke {@code window.vystormQuery} im Overlay (bis 0.6.0). Client ≥ 0.7.0 setzt das Bit nie. */
    public static final int CAP_JS_BRIDGE = 1 << 6;

    // ------------------------------------------------------------------ WELCOME-Flags

    /** Server erlaubt die Anzeige im Spiel (sonst nur externer Browser). */
    public static final int SERVER_OVERLAY = 1;
    /** Server schickt native Elemente. */
    public static final int SERVER_UI = 1 << 1;

    // ------------------------------------------------------------------ OPEN_REQUEST-Ziele

    /** Anzeige im Spiel (bis 0.6.0 das Overlay). Client ≥ 0.7.0 fragt nur {@link #TARGET_EXTERNAL} an. */
    public static final int TARGET_OVERLAY = 0;
    /** Login-Link im Systembrowser öffnen. */
    public static final int TARGET_EXTERNAL = 1;
    /** Stilles Anmelden des vorgewärmten, unsichtbaren Browsers nach dem Join (bis 0.6.0). */
    public static final int TARGET_PRELOAD = 2;

    // ------------------------------------------------------------------ DENIED-Gründe

    public static final int DENY_WEB_OFF = 1;
    public static final int DENY_UNKNOWN_MODULE = 2;
    public static final int DENY_NO_PERMISSION = 3;
    public static final int DENY_RATE_LIMITED = 4;
    public static final int DENY_OTHER = 5;

    // ------------------------------------------------------------------ CLOSED-Gründe

    public static final int CLOSED_USER = 0;
    public static final int CLOSED_SERVER = 1;
    public static final int CLOSED_ERROR = 2;
    public static final int CLOSED_BLOCKED_NAVIGATION = 3;

    // ------------------------------------------------------------------ CLIENT_STATE-Flags

    /** Spieler hat die nativen Elemente ausgeblendet. */
    public static final int STATE_UI_HIDDEN = 1;
    /** Anzeige im Spiel ist gerade offen (bis 0.6.0 das Overlay; Client ≥ 0.7.0 setzt das Bit nie). */
    public static final int STATE_OVERLAY_OPEN = 1 << 1;
    /**
     * Ab Protokoll 2: der Spieler hat dem festgepinnten Web-Ursprung für diesen Server zugestimmt und der Client würde ein
     * SHOW befolgen (Anzeige im Spiel nutzbar, Server-Öffnen erlaubt; mit dem Systembrowser seit 0.7.0 also nie). Nur ein Hinweis für den Server, damit er SHOW statt eines
     * Kistenmenüs schickt; enthält weder Token noch URL. Der Client prüft die Zustimmung bei jedem SHOW/OPEN selbst –
     * ein gefälschtes Bit bringt dem Server nichts.
     */
    public static final int STATE_ORIGIN_TRUSTED = 1 << 2;

    // ------------------------------------------------------------------ REJECT-Codes

    public static final int REJECT_INVALID = 1;
    public static final int REJECT_LIMIT = 2;
    public static final int REJECT_UNKNOWN_TYPE = 3;
    public static final int REJECT_UNKNOWN_ID = 4;
    public static final int REJECT_RATE = 5;

    // ------------------------------------------------------------------ vystorm:panel (Client 0.8.0 / Core 0.25.0)

    /** Kanal für native Panels (Screens aus Server-JSON), siehe {@code PROTOCOL.md} §8. @since Client 0.8.0 */
    public static final String CHANNEL_PANEL = "vystorm:panel";

    /** Höchstgröße einer Nachricht Client → Server auf {@code vystorm:panel} (Formulare). */
    public static final int MAX_PANEL_SERVERBOUND_BYTES = 4096;
    /** Höchstgröße einer Nachricht Server → Client auf {@code vystorm:panel}. */
    public static final int MAX_PANEL_CLIENTBOUND_BYTES = 32 * 1024;

    /** vystorm:panel, Client → Server. */
    public static final int PANEL_HELLO = 0x20;
    public static final int PANEL_REQUEST = 0x21;
    public static final int PANEL_ACTION = 0x22;
    public static final int PANEL_CLOSED = 0x23;
    public static final int PANEL_BLOB_NEED = 0x24;
    public static final int PANEL_ERROR = 0x2F;
    /** vystorm:panel, Server → Client. */
    public static final int PANEL_WELCOME = 0x30;
    public static final int PANEL_OPEN = 0x31;
    public static final int PANEL_PATCH = 0x32;
    public static final int PANEL_RESULT = 0x33;
    public static final int PANEL_CLOSE = 0x34;
    public static final int PANEL_BEGIN = 0x35;
    public static final int PANEL_CHUNK = 0x36;
    public static final int PANEL_DENIED = 0x37;

    /** Native Panels auf {@code vystorm:panel} (Client ≥ 0.8.0). */
    public static final int CAP_PANELS = 1 << 7;

    /**
     * Erweiterungen der nativen Elemente (Client ≥ 0.9.0): Platzhalter {@code {data:…}} und Bedingungen
     * {@code present}/{@code equalsViewer} in Tooltip-Regeln, Marker an Entities ({@code entity}, {@code yOffset}) und auf
     * dem Boden ({@code ground}), HUD-Liste {@code list}, {@code bar.rate}, {@code stack} für HUD-Elemente und Toasts mit
     * Aktion ({@code action}, Antwort {@link #UI_ACTION}). Der Server schickt diese Felder/Elemente nur mit diesem Bit.
     */
    public static final int CAP_UI_EXTENSIONS = 1 << 8;

    /** Server spricht {@code vystorm:panel} (Core ≥ 0.25.0); Client schickt danach P_HELLO. */
    public static final int SERVER_PANELS = 1 << 2;
}
