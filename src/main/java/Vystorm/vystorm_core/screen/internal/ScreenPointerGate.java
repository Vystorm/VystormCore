package Vystorm.vystorm_core.screen.internal;

import java.util.Objects;

/**
 * Rate limit of {@code ScreenInput.Pointer} samples of one screen (0.72.0): at most one per server tick,
 * and only when widget or position changed since the last delivered sample. A sample refused because its tick already
 * had one is not kept: the runtime reads the player's rotation again at the start of every tick while samples are
 * asked for, so the newest position always follows one tick later. Pure.
 */
final class ScreenPointerGate {
    /** Positions closer than this (panel units, far below a map pixel) count as equal. */
    static final double EPSILON = 1e-5;

    private long lastTick = Long.MIN_VALUE;
    private String lastId;
    private double lastU = Double.NaN, lastV = Double.NaN;

    /** Offers a sample in {@code tick} ({@code id} null: over no tracked widget); true when it must be delivered now. */
    boolean offer(long tick, String id, double u, double v) {
        if (id == null) { lastId = null; return false; }
        if (Objects.equals(id, lastId) && Math.abs(u - lastU) < EPSILON && Math.abs(v - lastV) < EPSILON) return false;
        if (tick == lastTick) return false;
        lastTick = tick;
        lastId = id;
        lastU = u;
        lastV = v;
        return true;
    }
}
