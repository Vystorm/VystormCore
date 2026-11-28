package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.visual.BlockPosition;

/**
 * {@link Vystorm.vystorm_core.picker.PickProtection#BUILD} for one cell (design 4.6): the answer of
 * {@code api.area().mayEdit(..)} for the cell's chunk, tested per cell when partial; a player with the pick's bypass
 * permission skips it. Pure. Core-internal.
 */
public final class PickProtectionCheck {
    private PickProtectionCheck() {}

    /** Null = allowed; otherwise who said no ({@code regions}, a guard's key, {@code guard-failed} or {@code unknown}). */
    public static String denied(EditVerdict verdict, BlockPosition cell, boolean bypass) {
        if (bypass) return null;
        if (verdict == null) return "unknown";
        return switch (verdict) {
            case EditVerdict.Allow a -> null;
            case EditVerdict.Deny d -> d.reasonKey().isEmpty() ? "unknown" : d.reasonKey();
            case EditVerdict.Partial p -> {
                boolean ok;
                try { ok = p.allowed().allowed(cell.x(), cell.y(), cell.z()); }
                catch (RuntimeException failure) { yield "guard-failed"; }
                yield ok ? null : "partial";
            }
        };
    }
}
