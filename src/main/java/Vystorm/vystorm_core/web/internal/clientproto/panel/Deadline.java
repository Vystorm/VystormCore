// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Deadline.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import Vystorm.vystorm_core.web.internal.clientproto.ProtocolException;
import java.util.function.LongSupplier;

/** Zeitlimit für Arbeit auf dem Worker-Thread (Entpacken, Parsen, Patchen); {@link #check()} wirft nach Ablauf. */
public final class Deadline {
    public static final Deadline NONE = new Deadline(Long.MAX_VALUE, () -> 0L);

    private final long end;
    private final LongSupplier nanos;

    private Deadline(long end, LongSupplier nanos) {
        this.end = end;
        this.nanos = nanos;
    }

    public static Deadline after(long millis, LongSupplier nanos) {
        return new Deadline(nanos.getAsLong() + millis * 1_000_000L, nanos);
    }

    public void check() {
        if (end != Long.MAX_VALUE && nanos.getAsLong() > end) throw new ProtocolException("timeout");
    }
}
