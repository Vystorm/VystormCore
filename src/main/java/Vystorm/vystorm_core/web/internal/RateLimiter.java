package Vystorm.vystorm_core.web.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token-Bucket-Ratenlimits je Schlüssel (IP, Sitzung, Route). Ein Bucket fasst {@code burst} Anfragen und füllt sich mit
 * {@code perMinute} pro Minute. Die Zahl der Schlüssel ist begrenzt; volle Buckets werden regelmäßig verworfen.
 * Threadsicher. Core-intern.
 *
 * @since 0.17.0
 */
public final class RateLimiter {
    private static final class Bucket {
        double tokens;
        long last;
        final double capacity;
        Bucket(double capacity, long now) { this.capacity = capacity; this.tokens = capacity; this.last = now; }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanos;
    private final int maxKeys;

    public RateLimiter(LongSupplier nanos, int maxKeys) {
        this.nanos = nanos;
        this.maxKeys = maxKeys;
    }

    /**
     * Schlüssel für Grenzen je Client: IPv4 unverändert, IPv6 als /64-Präfix ({@code 2001:db8:1:2::/64}), IPv4-in-IPv6 als
     * IPv4. Ein Anschluss bekommt meist ein ganzes /64; je Adresse zu zählen ließe ihn die Limits umgehen und die
     * Bucket-Tabelle ({@code maxKeys}) mit neuen Adressen fluten.
     *
     * @since 0.18.1
     */
    public static String clientKey(String ip) {
        java.net.InetAddress address = EmbeddedHttpServer.literal(ip);
        if (address == null) return ip == null ? "?" : ip;
        byte[] b = address.getAddress();
        if (b.length == 4) return address.getHostAddress();
        StringBuilder out = new StringBuilder(24);
        for (int i = 0; i < 8; i += 2) out.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
        return out.append(":/64").toString();
    }

    /** Standard-Burst zu einem Minutenlimit: ein Viertel, mindestens 5, höchstens das Limit. */
    public static int burstFor(int perMinute) { return Math.min(perMinute, Math.max(5, perMinute / 4)); }

    /** @return 0 wenn erlaubt, sonst Sekunden bis zum nächsten freien Platz (mindestens 1) */
    public long acquire(String key, int perMinute) { return acquire(key, perMinute, burstFor(perMinute)); }

    /** @return 0 wenn erlaubt, sonst Sekunden bis zum nächsten freien Platz (mindestens 1) */
    public long acquire(String key, int perMinute, int burst) {
        long now = nanos.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                purgeIdle();
                if (buckets.size() >= maxKeys) return 60;
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(Math.max(1, burst), now));
        }
        double perNano = perMinute / 60_000_000_000.0;
        synchronized (bucket) {
            long elapsed = Math.max(0, now - bucket.last);
            bucket.tokens = Math.min(bucket.capacity, bucket.tokens + elapsed * perNano);
            bucket.last = now;
            if (bucket.tokens >= 1) { bucket.tokens -= 1; return 0; }
            double missing = 1 - bucket.tokens;
            return Math.max(1, (long) Math.ceil(missing / perNano / 1_000_000_000.0));
        }
    }

    /** Verwirft Buckets, die seit mindestens zwei Minuten unbenutzt sind (sie wären ohnehin wieder voll). */
    public void purgeIdle() {
        long now = nanos.getAsLong();
        buckets.values().removeIf(b -> { synchronized (b) { return now - b.last > 120_000_000_000L; } });
    }

    public int size() { return buckets.size(); }

    public void clear() { buckets.clear(); }
}
