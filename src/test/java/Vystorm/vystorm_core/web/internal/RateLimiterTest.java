package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final AtomicLong nanos = new AtomicLong(0);

    @Test
    void givenBurstUsed_whenMoreRequests_thenDeniedUntilRefill() {
        RateLimiter limiter = new RateLimiter(nanos::get, 100);
        for (int i = 0; i < 5; i++) assertEquals(0, limiter.acquire("k", 60, 5), "burst " + i);
        long retry = limiter.acquire("k", 60, 5);
        assertTrue(retry >= 1);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(0, limiter.acquire("k", 60, 5), "one token per second at 60/min");
        assertTrue(limiter.acquire("k", 60, 5) > 0);
    }

    @Test
    void givenDifferentKeys_whenLimited_thenIndependent() {
        RateLimiter limiter = new RateLimiter(nanos::get, 100);
        for (int i = 0; i < 5; i++) limiter.acquire("a", 10, 5);
        assertTrue(limiter.acquire("a", 10, 5) > 0);
        assertEquals(0, limiter.acquire("b", 10, 5));
    }

    @Test
    void givenKeyLimitReached_whenNewKey_thenDeniedButIdleKeysPurged() {
        RateLimiter limiter = new RateLimiter(nanos::get, 2);
        limiter.acquire("a", 10);
        limiter.acquire("b", 10);
        assertTrue(limiter.acquire("c", 10) > 0, "full table denies new keys");
        nanos.addAndGet(121_000_000_000L);
        assertEquals(0, limiter.acquire("c", 10), "idle buckets purged");
    }
}
