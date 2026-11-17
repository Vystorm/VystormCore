package Vystorm.vystorm_core.web;

/**
 * Bricht eine Anfrage mit einem HTTP-Status und einer Meldung für den Browser ab. Die Meldung wird unverändert
 * angezeigt; also keine internen Details hineinschreiben.
 *
 * @since 0.17.0
 */
public class WebException extends RuntimeException {
    private final int status;

    /** @param status 400–599 */
    public WebException(int status, String message) {
        super(message == null ? "" : message, null, false, false);
        if (status < 400 || status > 599) throw new IllegalArgumentException("status must be 400..599");
        this.status = status;
    }

    public int status() { return status; }

    public static WebException badRequest(String message) { return new WebException(400, message); }
    public static WebException forbidden(String message) { return new WebException(403, message); }
    public static WebException notFound(String message) { return new WebException(404, message); }
    public static WebException conflict(String message) { return new WebException(409, message); }
}
