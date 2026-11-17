package Vystorm.vystorm_core.network.core;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Port protection for the hub: optional IP/CIDR allow list, a cap on concurrent unauthenticated connections, and a
 * temporary ban after repeated failed handshakes (spec: 5 failures within 1 minute → 10 minutes). Thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ConnectionGuard {
    public static final int FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(1);
    public static final Duration BAN = Duration.ofMinutes(10);

    private final List<Cidr> allow;
    private final Semaphore pending;
    private final Clock clock;
    private final Map<InetAddress, Deque<Long>> failures = new HashMap<>();
    private final Map<InetAddress, Long> bannedUntil = new HashMap<>();

    public ConnectionGuard(List<String> allowCidrs, int maxPending, Clock clock) {
        this.allow = new ArrayList<>();
        for (String entry : allowCidrs) Cidr.parse(entry).ifPresent(allow::add);
        this.pending = new Semaphore(maxPending);
        this.clock = clock;
    }

    public synchronized boolean allow(InetAddress address) {
        Long until = bannedUntil.get(address);
        if (until != null) {
            if (clock.millis() < until) return false;
            bannedUntil.remove(address);
        }
        return allow.isEmpty() || allow.stream().anyMatch(c -> c.contains(address));
    }

    /** @return true if this failure banned the address */
    public synchronized boolean recordFailure(InetAddress address) {
        long now = clock.millis();
        Deque<Long> times = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW.toMillis()) times.removeFirst();
        if (times.size() >= FAILURES) {
            failures.remove(address);
            bannedUntil.put(address, now + BAN.toMillis());
            return true;
        }
        return false;
    }

    /** A slot for one unauthenticated connection, or empty when the cap is reached. Close it after the handshake. */
    public Optional<AutoCloseable> pendingSlot() {
        if (!pending.tryAcquire()) return Optional.empty();
        return Optional.of(new AutoCloseable() {
            private boolean released;
            @Override public synchronized void close() { if (!released) { released = true; pending.release(); } }
        });
    }

    /** One allow-list entry: a single address or {@code address/prefix} for IPv4 and IPv6. */
    record Cidr(byte[] network, int prefix) {
        static Optional<Cidr> parse(String text) {
            if (text == null || text.isBlank()) return Optional.empty();
            String[] parts = text.trim().split("/", 2);
            try {
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
                if (prefix < 0 || prefix > address.length * 8) return Optional.empty();
                return Optional.of(new Cidr(address, prefix));
            } catch (UnknownHostException | NumberFormatException bad) {
                return Optional.empty();
            }
        }

        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != network.length) return false;
            int bits = network.length * 8;
            BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                    .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE));
            return new BigInteger(1, network).and(mask).equals(new BigInteger(1, other).and(mask));
        }
    }
}
