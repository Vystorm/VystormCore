package Vystorm.vystorm_core.network.core;

import java.io.IOException;

/**
 * A frame could not be encoded or decoded; the connection that produced it must be closed.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FrameException extends IOException {
    public FrameException(String message) { super(message); }
    public FrameException(String message, Throwable cause) { super(message, cause); }
}
