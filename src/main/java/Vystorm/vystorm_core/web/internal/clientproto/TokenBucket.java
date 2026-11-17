// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, TokenBucket.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto;

import java.util.function.LongSupplier;

/** Einfacher Token-Bucket (nicht threadsicher; pro Verbindung auf einem Thread benutzen). */
public final class TokenBucket {
    private final LongSupplier clockNanos;
    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last;

    /**
     * @param perSecond Nachfüllrate
     * @param burst     Höchstbestand (und Startbestand)
     */
    public TokenBucket(double perSecond, double burst, LongSupplier clockNanos) {
        if (perSecond <= 0 || burst < 1) throw new IllegalArgumentException("rate/burst");
        this.perSecond = perSecond;
        this.burst = burst;
        this.clockNanos = clockNanos;
        this.tokens = burst;
        this.last = clockNanos.getAsLong();
    }

    /** Nimmt ein Token, wenn vorhanden. */
    public boolean tryAcquire() {
        long now = clockNanos.getAsLong();
        double elapsed = Math.max(0, now - last) / 1e9;
        last = now;
        tokens = Math.min(burst, tokens + elapsed * perSecond);
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
