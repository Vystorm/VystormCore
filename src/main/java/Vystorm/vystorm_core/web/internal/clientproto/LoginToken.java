// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, LoginToken.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import java.util.function.LongSupplier;

/**
 * Einmal-Login-Token von Core (256 Bit, base64url ohne Padding, genau 43 Zeichen). Lebt nur im Speicher, wird genau
 * einmal herausgegeben ({@link #consume()}) und verfällt clientseitig nach der vom Server genannten Zeit.
 * {@link #toString()} zeigt das Token nie.
 */
public final class LoginToken {
    /** Länge wie {@code SessionStore.TOKEN_LENGTH} in Core. */
    public static final int LENGTH = 43;
    /** Obergrenze der Gültigkeit, egal was der Server schickt (Core erlaubt höchstens 600 s). */
    public static final int MAX_TTL_SECONDS = 600;

    private final LongSupplier clockMillis;
    private final long expiresAt;
    private String value;

    private LoginToken(String value, long expiresAt, LongSupplier clockMillis) {
        this.value = value;
        this.expiresAt = expiresAt;
        this.clockMillis = clockMillis;
    }

    /**
     * @param ttlSeconds vom Server genannte Restlaufzeit; wird auf {@code [1, 600]} begrenzt
     * @throws ProtocolException wenn das Token nicht wohlgeformt ist
     */
    public static LoginToken of(String raw, int ttlSeconds, LongSupplier clockMillis) {
        if (!wellFormed(raw)) throw new ProtocolException("token malformed");
        int ttl = Math.max(1, Math.min(MAX_TTL_SECONDS, ttlSeconds));
        return new LoginToken(raw, clockMillis.getAsLong() + ttl * 1000L, clockMillis);
    }

    /** Genau 43 Zeichen aus {@code [A-Za-z0-9_-]}. */
    public static boolean wellFormed(String raw) {
        if (raw == null || raw.length() != LENGTH) return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Ob das Token noch nicht benutzt und nicht abgelaufen ist. */
    public synchronized boolean usable() {
        return value != null && clockMillis.getAsLong() < expiresAt;
    }

    /**
     * Gibt das Token genau einmal heraus und vergisst es.
     *
     * @throws IllegalStateException wenn bereits benutzt oder abgelaufen
     */
    public synchronized String consume() {
        if (value == null) throw new IllegalStateException("login token already used");
        String out = value;
        value = null;
        if (clockMillis.getAsLong() >= expiresAt) throw new IllegalStateException("login token expired");
        return out;
    }

    /** Verwirft das Token ohne Benutzung. */
    public synchronized void discard() { value = null; }

    @Override public String toString() { return "LoginToken[redacted]"; }
}
