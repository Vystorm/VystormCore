package Vystorm.vystorm_core.packet.internal.view;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counters and timing samples of the packet view (/vadmin packets, proofs) (thread-safe, cheap to update). */
public final class ViewCounters {
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Samples> samples = new ConcurrentHashMap<>();

    public void inc(String key) { add(key, 1); }

    public void add(String key, long n) { counters.computeIfAbsent(key, k -> new LongAdder()).add(n); }

    public long get(String key) { LongAdder a = counters.get(key); return a == null ? 0 : a.sum(); }

    public void sample(String key, double value) { samples.computeIfAbsent(key, k -> new Samples()).add(value); }

    public Samples samples(String key) { return samples.get(key); }

    public void reset() { counters.clear(); samples.clear(); }

    /** Every counter by name (a sorted snapshot). */
    public Map<String, Long> counters() {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : counters.entrySet()) out.put(e.getKey(), e.getValue().sum());
        return out;
    }

    /** A bounded reservoir (last 4096 values) with count, mean and percentiles. */
    public static final class Samples {
        private final double[] ring = new double[4096];
        private long n;
        private double sum, max;

        synchronized void add(double v) { ring[(int) (n++ % ring.length)] = v; sum += v; max = Math.max(max, v); }

        public synchronized long count() { return n; }

        public synchronized double mean() { return n == 0 ? 0 : sum / n; }

        public synchronized double max() { return max; }

        public synchronized double percentile(double p) {
            int k = (int) Math.min(n, ring.length);
            if (k == 0) return 0;
            double[] a = java.util.Arrays.copyOf(ring, k);
            java.util.Arrays.sort(a);
            return a[Math.min(k - 1, (int) Math.floor(p * (k - 1) + 0.5))];
        }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "n=%d mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f", count(), mean(), percentile(.5), percentile(.95), percentile(.99), max());
        }
    }

    /** Every counter and sample, sorted, one per line. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(counters).entrySet()) sb.append(e.getKey()).append('=').append(e.getValue().sum()).append('\n');
        for (Map.Entry<String, Samples> e : new TreeMap<>(samples).entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return sb.toString();
    }
}
