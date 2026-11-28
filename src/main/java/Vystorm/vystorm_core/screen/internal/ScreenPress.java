package Vystorm.vystorm_core.screen.internal;

/**
 * Right-button hold state of one screen (0.72.0). Pure, driven by a millisecond clock.
 *
 * <p>The server never hears "button up". A held right button makes the client send "use" again every 4 client
 * ticks (Minecraft 26.2 and 26.3 {@code Minecraft.tick}: {@code rightClickDelay} counts down before
 * {@code handleKeybinds}, and {@code startUseItem} sets it to 4), i.e. every 200 ms. So the first use of a hold is
 * "down", and "up" is the first moment {@code releaseMillis} passed without another use.
 */
final class ScreenPress {
    /** Repeat interval of a held right button in the client (4 ticks). */
    static final long REPEAT_MILLIS = 200;

    private final long releaseMillis;
    private boolean down;
    private long lastUse;

    ScreenPress(long releaseMillis) {
        if (releaseMillis <= REPEAT_MILLIS) throw new IllegalArgumentException("release time must be longer than the repeat interval");
        this.releaseMillis = releaseMillis;
    }

    boolean down() { return down; }

    /**
     * A "use" arrived at {@code now}; true when it starts a hold (report "down" before its click). Callers ask
     * {@link #released} first, so a use after a gap no tick saw (server stall) ends the old hold before it starts one.
     */
    boolean use(long now) {
        boolean starts = !down;
        down = true;
        lastUse = now;
        return starts;
    }

    /** True once at the first check {@code releaseMillis} after the last use: report "up". */
    boolean released(long now) {
        if (!down || now - lastUse < releaseMillis) return false;
        down = false;
        return true;
    }

    /** The screen closes: true when the button was down (report "up" before Closed). */
    boolean end() {
        boolean was = down;
        down = false;
        return was;
    }
}
