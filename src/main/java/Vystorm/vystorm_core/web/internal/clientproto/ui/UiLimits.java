// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, UiLimits.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

/** Grenzen für native Elemente. Alles darüber wird abgelehnt (nicht gekürzt), damit Fehler auffallen. */
public final class UiLimits {
    private UiLimits() {}

    /** panel + bar + slots zusammen. */
    public static final int MAX_HUD_ELEMENTS = 32;
    public static final int MAX_MARKERS = 64;
    public static final int MAX_TOOLTIP_RULES = 128;
    /** Wartende Toasts; ältere fallen heraus. */
    public static final int MAX_QUEUED_TOASTS = 5;

    public static final int MAX_TEXT_CHARS = 256;
    public static final int MAX_SEGMENTS = 16;
    public static final int MAX_PANEL_LINES = 12;
    public static final int MAX_TOOLTIP_LINES = 8;
    public static final int MAX_SLOTS = 12;
    public static final int MAX_KEY_CHARS = 4;

    public static final int MAX_OFFSET = 4000;
    public static final int MAX_PANEL_WIDTH = 320;
    public static final int MIN_BAR_WIDTH = 8;
    public static final int MAX_BAR_WIDTH = 400;
    public static final int MIN_BAR_HEIGHT = 2;
    public static final int MAX_BAR_HEIGHT = 16;
    public static final int MAX_BAR_SEGMENTS = 20;
    public static final int MIN_SLOT_SIZE = 16;
    public static final int MAX_SLOT_SIZE = 32;
    public static final int MAX_SLOT_GAP = 16;
    public static final int MAX_ORDER = 1000;

    /** Größte Lebensdauer eines Elements (1 h); 0 = unbegrenzt bis REMOVE/CLEAR/Trennung. */
    public static final long MAX_TTL_MS = 3_600_000L;
    public static final long MAX_COOLDOWN_MS = 3_600_000L;
    public static final int MIN_TOAST_MS = 1000;
    public static final int MAX_TOAST_MS = 10_000;

    /** Weltkoordinaten innerhalb der Weltgrenze. */
    public static final double MAX_COORD = 30_000_000d;
    public static final double MAX_MARKER_DISTANCE = 1024d;

    /** Größte Verschachtelungstiefe von JSON (Objekte/Listen). */
    public static final int MAX_JSON_DEPTH = 8;
    /** Größte Länge von {@code customData.path} und {@code customData.equals}. */
    public static final int MAX_DATA_PATH = 128;
    public static final int MAX_DATA_VALUE = 128;

    // ------------------------------------------------------------------ Client 0.9.0 (CAP_UI_EXTENSIONS)

    /** Platzhalter {@code {data:…}} je Tooltip-Zeile bzw. je Regel. */
    public static final int MAX_PLACEHOLDERS_PER_LINE = 4;
    /** {@code match.customData} als Liste: höchstens so viele Bedingungen (UND). */
    public static final int MAX_DATA_CONDITIONS = 4;
    public static final int MAX_PLACEHOLDERS_PER_RULE = 16;
    /** Eingesetzter Wert eines Platzhalters (Zeichen, danach gekürzt mit „…“). */
    public static final int MAX_PLACEHOLDER_VALUE_CHARS = 64;
    /** {@code list}: Zeilen, Breite, Symbolgröße. */
    public static final int MAX_LIST_ROWS = 12;
    public static final int MIN_LIST_WIDTH = 40;
    /** {@code bar.rate} und {@code progress.rate}: Einheiten pro Sekunde (±). */
    public static final double MAX_RATE = 1e9;
    /** {@code marker.yOffset} in Blöcken (±). */
    public static final double MAX_MARKER_Y_OFFSET = 64d;
    /** Toast-Aktion: Beschriftung (Zeichen). */
    public static final int MAX_ACTION_LABEL_CHARS = 32;
}
