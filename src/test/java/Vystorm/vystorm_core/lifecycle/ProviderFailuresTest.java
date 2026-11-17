package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderFailuresTest {
    @AfterEach
    void reset() { ProviderFailures.clock = System::nanoTime; ProviderFailures.reset(); }

    @Test
    void givenRepeatedFailures_whenReported_thenThrottledPerKeyAndSuppressedCounted() {
        AtomicLong now = new AtomicLong(1_000);
        ProviderFailures.clock = now::get;
        assertEquals(0, ProviderFailures.admit("k"));
        assertEquals(-1, ProviderFailures.admit("k"));
        assertEquals(-1, ProviderFailures.admit("k"));
        assertEquals(0, ProviderFailures.admit("other"));
        now.addAndGet(ProviderFailures.INTERVAL_NANOS);
        assertEquals(2, ProviderFailures.admit("k"));
        ProviderFailures.report("R", "p", "op", new IllegalStateException()); // ohne API: kein Fehler
    }
}
