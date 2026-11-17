package Vystorm.vystorm_core.stat;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A stat assembled from replaceable, source-owned contributions. Basis values are ADD contributions too.
 * Formula: sum(ADD) * (1 + sum(MULTIPLY)) * product(MULTIPLY_TOTAL).
 * MULTIPLY uses fractions (0.2 = +20%); MULTIPLY_TOTAL uses factors (1.2 = x1.2).
 * Use a distinct source for each equipment slot/effect/component. No Bukkit or global player state is retained.
 * Instances are synchronized; returned contribution maps are immutable snapshots. @since 0.35.0
 */
public final class StatSum {
    public enum Operation { ADD, MULTIPLY, MULTIPLY_TOTAL }

    public record Contribution(Operation operation, double value) {
        public Contribution {
            Objects.requireNonNull(operation, "operation");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Stat contribution must be finite");
        }
    }

    // Stable source order makes the result independent of equipment/event insertion order.
    private final Map<String, Contribution> sources = new TreeMap<>();

    public StatSum() {}

    public StatSum(Map<String, Contribution> contributions) {
        contributions.forEach(this::put);
    }

    /** Replaces this source instead of accumulating repeated equip or refresh events. */
    public synchronized StatSum put(String source, Contribution contribution) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Stat source is required");
        sources.put(source, Objects.requireNonNull(contribution, "contribution"));
        return this;
    }

    public StatSum put(String source, Operation operation, double value) {
        return put(source, new Contribution(operation, value));
    }

    public synchronized boolean remove(String source) { return sources.remove(source) != null; }

    public synchronized void clear() { sources.clear(); }

    public synchronized Map<String, Contribution> contributions() {
        return Collections.unmodifiableMap(new TreeMap<>(sources));
    }

    public synchronized double value() {
        double additive = 0, relative = 0, total = 1;
        for (Contribution contribution : sources.values()) {
            switch (contribution.operation()) {
                case ADD -> additive += contribution.value();
                case MULTIPLY -> relative += contribution.value();
                case MULTIPLY_TOTAL -> total *= contribution.value();
            }
        }
        double result = additive * (1 + relative) * total;
        if (!Double.isFinite(result)) throw new ArithmeticException("Stat sum overflow");
        return result;
    }
}
