package Vystorm.vystorm_core.network.core;

import java.time.Duration;
import java.util.Random;

/**
 * Reconnect delays 1, 2, 4, 8, 16, 30, 30 … seconds plus 0–20 % jitter. Not thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class Backoff {
    private final Duration base;
    private final Duration cap;
    private final Random random;
    private int attempt;

    public Backoff() { this(Duration.ofSeconds(1), Duration.ofSeconds(30), new Random()); }

    public Backoff(Duration base, Duration cap, Random random) {
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    public Duration next() {
        long baseMillis = base.toMillis();
        long raw = attempt >= 30 ? cap.toMillis() : Math.min(cap.toMillis(), baseMillis << attempt);
        attempt++;
        long jitter = (long) (raw * 0.2 * random.nextDouble());
        return Duration.ofMillis(raw + jitter);
    }

    /** Number of delays handed out since the last {@link #reset()}; used to rate-limit log lines. */
    public int attempt() { return attempt; }

    public void reset() { attempt = 0; }
}
