package Vystorm.vystorm_core.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;

/**
 * Antwort einer {@link WebRoute}: immer JSON ({@code application/json; charset=utf-8}, {@code Cache-Control: no-store}).
 * Unveränderlich.
 *
 * @since 0.17.0
 */
public final class WebResponse {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_MESSAGE = 300;

    private final int status;
    private final byte[] body;

    private WebResponse(int status, byte[] body) {
        this.status = status;
        this.body = body;
    }

    /** HTTP 200 mit {@code value} als JSON (Gson; ein {@link JsonElement} wird direkt übernommen). */
    public static WebResponse json(Object value) { return json(200, value); }

    /** {@code value} als JSON mit eigenem Status (200–299 oder 400–599). */
    public static WebResponse json(int status, Object value) {
        if (!(status >= 200 && status <= 299) && !(status >= 400 && status <= 599))
            throw new IllegalArgumentException("status must be 2xx, 4xx or 5xx");
        String json = value instanceof JsonElement element ? GSON.toJson(element) : GSON.toJson(value);
        return new WebResponse(status, json.getBytes(StandardCharsets.UTF_8));
    }

    /** HTTP 204 ohne Körper. */
    public static WebResponse noContent() { return new WebResponse(204, new byte[0]); }

    /** {@code {"error": "<code>", "message": "<message>"}} mit Status 400–599. */
    public static WebResponse error(int status, String message) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("status must be 400..599");
        JsonObject o = new JsonObject();
        o.addProperty("error", code(status));
        String text = message == null ? "" : message;
        o.addProperty("message", text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) : text);
        return new WebResponse(status, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    public int status() { return status; }

    /** Der JSON-Körper (Kopie). */
    public byte[] body() { return body.clone(); }

    /** Der JSON-Körper als Text. */
    public String bodyText() { return new String(body, StandardCharsets.UTF_8); }

    /** Kurzer, stabiler Fehlercode zu einem Status. */
    public static String code(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 409 -> "conflict";
            case 413 -> "payload_too_large";
            case 415 -> "unsupported_media_type";
            case 421 -> "misdirected_request";
            case 429 -> "rate_limited";
            case 500 -> "internal_error";
            case 503 -> "unavailable";
            case 504 -> "timeout";
            default -> status >= 500 ? "server_error" : "client_error";
        };
    }
}
