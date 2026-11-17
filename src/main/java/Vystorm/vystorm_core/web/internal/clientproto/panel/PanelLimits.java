// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelLimits.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

/** Grenzen und Konstanten von {@code vystorm:panel} (Spec §2–§3). Alles darüber wird abgelehnt, nicht gekürzt. */
public final class PanelLimits {
    private PanelLimits() {}

    // Schema
    public static final int SCHEMA_MIN = 1;
    public static final int SCHEMA_MAX = 1;

    // Felder (Bytes)
    public static final int MAX_PANEL_ID_BYTES = 64;
    public static final int MAX_ARG_BYTES = 128;
    public static final int MAX_ELEMENT_ID_BYTES = 64;
    public static final int MAX_ACTION_VALUE_BYTES = 3072;
    public static final int MAX_INLINE_JSON_BYTES = 30720;
    public static final int MAX_RESULT_JSON_BYTES = 2048;
    public static final int MAX_DENIED_MESSAGE_BYTES = 512;
    public static final int MAX_ERROR_DETAIL_BYTES = 128;
    public static final int MAX_CHUNK_BYTES = 30 * 1024;
    /** Roh-JSON bis hier inline, darüber immer Transfer mit Deflate (Plan-Entscheidung 1). */
    public static final int INLINE_MAX_BYTES = 8 * 1024;

    // Dokument/Patch/Blob (entpackt)
    public static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    public static final int MAX_ELEMENTS = 4096;
    public static final int MAX_DEPTH = 24;
    /** JSON-Verschachtelung (Objekte+Listen) vor dem Parsen; Elementtiefe 24 braucht höchstens ~3 Ebenen je Element. */
    public static final int MAX_JSON_DEPTH = 96;
    public static final int MAX_PATCH_BYTES = 128 * 1024;
    public static final int MAX_PATCH_OPS = 512;
    public static final int MAX_BLOB_BYTES = 4 * 1024 * 1024;
    public static final int MAX_INFLATE_RATIO = 64;
    public static final int MAX_TRANSFERS = 2;
    public static final long TRANSFER_TIMEOUT_MS = 10_000;
    public static final int MAX_BACK_STACK = 4;
    public static final long WORKER_TIMEOUT_MS = 200;
    public static final long WELCOME_TIMEOUT_MS = 30_000;
    public static final int MAX_ERRORS_PER_CONNECTION = 50;

    // Texte und Listen
    public static final int MAX_TEXT_SEGMENTS = 64;
    public static final int MAX_TEXT_CHARS = 2048;
    public static final int MAX_TOOLTIP_LINES = 8;
    public static final int MAX_OPTIONS = 256;
    public static final int MAX_TABLE_ROWS = 2000;
    public static final int MAX_COLUMNS = 16;
    public static final int MAX_TABS = 16;
    public static final int MAX_KV_ROWS = 64;
    public static final int MAX_LIST_ITEMS = 2000;
    public static final int MAX_INPUT_LENGTH = 256;
    public static final int MAX_FORM_BYTES = 3072;

    // Aktionsarten (P_ACTION kind)
    public static final int KIND_CLICK = 1, KIND_CHANGE = 2, KIND_SUBMIT = 3, KIND_SORT = 4, KIND_PAGE = 5,
            KIND_TAB = 6, KIND_SELECT = 7, KIND_NODE = 8, KIND_SEARCH = 9;

    // P_RESULT status
    public static final int STATUS_OK = 0, STATUS_ERROR = 1, STATUS_DENIED = 2, STATUS_STALE = 3, STATUS_RATE = 4;

    // P_OPEN flags
    public static final int FLAG_REPLACE = 1, FLAG_PUSH = 1 << 1, FLAG_KEEP_STATE = 1 << 2;
    public static final int FLAGS_ALL = FLAG_REPLACE | FLAG_PUSH | FLAG_KEEP_STATE;

    // P_CLOSED / P_CLOSE reason
    public static final int CLOSED_PLAYER = 0, CLOSED_REPLACED = 1, CLOSED_ERROR = 2, CLOSED_SERVER = 3;

    // features (P_HELLO/P_WELCOME)
    public static final int FEATURE_CHART = 1, FEATURE_GRAPH = 1 << 1, FEATURE_BLOB_IMAGE = 1 << 2, FEATURE_TIMER = 1 << 3;
    /** Was Client 0.8.0 / Core 0.25.0 können (Phase 1). */
    public static final int FEATURES_PHASE1 = FEATURE_TIMER;

    // Transfer
    public static final int TRANSFER_DOCUMENT = 1, TRANSFER_PATCH = 2, TRANSFER_BLOB = 3;
    public static final int CODEC_RAW = 0, CODEC_DEFLATE = 1;

    // P_ERROR codes
    public static final int ERR_INVALID = 1, ERR_LIMIT = 2, ERR_UNSUPPORTED = 3, ERR_UNKNOWN_ID = 4, ERR_TIMEOUT = 5,
            ERR_TRANSFER = 6;
}
