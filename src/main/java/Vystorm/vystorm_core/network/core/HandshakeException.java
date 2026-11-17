package Vystorm.vystorm_core.network.core;

import java.io.IOException;

/**
 * The peer could not be authenticated or speaks another protocol; the connection must be closed.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HandshakeException extends IOException {
    /** Why a handshake failed; safe to show to admins (never contains key material). */
    public enum Reason { PROTOCOL, AUTH, NAME_REJECTED, MALFORMED }

    private final Reason reason;

    public HandshakeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
