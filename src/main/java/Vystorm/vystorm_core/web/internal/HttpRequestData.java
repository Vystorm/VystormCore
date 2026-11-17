package Vystorm.vystorm_core.web.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eine HTTP-Anfrage unabhängig vom Transport (eingebetteter Server oder Gateway-Tunnel). Header-Namen sind klein
 * geschrieben. {@code clientIp} ist bereits die vertrauenswürdige Client-Adresse. Core-intern.
 *
 * @since 0.17.0
 */
public record HttpRequestData(String method, String target, Map<String, List<String>> headers, byte[] body, String clientIp, boolean secure) {
    public HttpRequestData {
        body = body == null ? new byte[0] : body;
        headers = headers == null ? Map.of() : headers;
    }

    /** Erster Wert eines Headers (Name beliebig geschrieben), sonst null. */
    public String header(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** Anzahl der Werte eines Headers. */
    public int headerCount(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null ? 0 : values.size();
    }
}
