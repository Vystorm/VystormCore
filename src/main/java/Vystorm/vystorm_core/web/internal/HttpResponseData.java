package Vystorm.vystorm_core.web.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eine HTTP-Antwort unabhängig vom Transport. Header in Reihenfolge, Namen dürfen mehrfach vorkommen
 * ({@code Set-Cookie}). {@code close} bittet den Transport, die Verbindung danach zu schließen. Ist {@code stream}
 * gesetzt, folgt der Körper fortlaufend aus dem Stream statt aus {@code body}. Core-intern.
 *
 * @since 0.17.0
 */
public record HttpResponseData(int status, List<Map.Entry<String, String>> headers, byte[] body, boolean close, ResponseStream stream) {
    public HttpResponseData {
        headers = headers == null ? List.of() : List.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public HttpResponseData(int status, List<Map.Entry<String, String>> headers, byte[] body, boolean close) {
        this(status, headers, body, close, null);
    }

    public String header(String name) {
        for (Map.Entry<String, String> h : headers) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    public List<String> headers(String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) if (h.getKey().equalsIgnoreCase(name)) out.add(h.getValue());
        return out;
    }

    public String bodyText() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
}
