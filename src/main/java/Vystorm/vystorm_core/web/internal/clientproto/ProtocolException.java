// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, ProtocolException.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

/**
 * Ungültige oder zu große Nachricht. Die Meldung enthält nie den Inhalt der Nachricht (kann Tokens enthalten),
 * nur Feldname und Grund.
 */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message, null, false, false);
    }
}
