package Vystorm.vystorm_core.blocks;

import java.util.Objects;

/**
 * Answer of {@code api.area().mayEdit(ask)} for one chunk.
 *
 * @since 0.69.0
 */
public sealed interface EditVerdict {
    /** Every cell of the asked part of the chunk may change. */
    record Allow() implements EditVerdict {}

    /**
     * No cell may change. {@code reasonKey} names who said no: {@code regions} for Core's regions, or the key a guard
     * chose (a guard that failed gives {@code guard-failed}).
     */
    record Deny(String reasonKey) implements EditVerdict {
        public Deny { reasonKey = reasonKey == null ? "" : reasonKey; }
    }

    /** Some cells may change: ask {@code allowed} per cell. The test is thread-safe and pure. */
    record Partial(CellTest allowed) implements EditVerdict {
        public Partial { Objects.requireNonNull(allowed, "allowed"); }
    }
}
