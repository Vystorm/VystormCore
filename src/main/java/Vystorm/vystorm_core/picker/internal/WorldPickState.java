package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickVerdict;

/**
 * The timing rules of one world pick (design 4.3, 4.7), pure with an injected clock so tests need no server:
 * <ul>
 *   <li>phase CHOOSING, OPTIONS (the options menu is open; the pick waits), PROMPT (typing coordinates) or CONFIRM
 *       (the confirm view of an area pick is open; the pick waits);</li>
 *   <li>a start grace of {@value #GRACE} ms (the click that opened the pick must not choose at once), also after the
 *       options or the prompt closed;</li>
 *   <li>one physical click raises up to three events (interact, arm swing, entity hit): only the first per
 *       {@value #DEBOUNCE} ms counts; the arm swing that follows dropping an item is not a click ({@value #DROP} ms);</li>
 *   <li>the sneak that closed the options menu does not open it again ({@value #SNEAK_AFTER_OPTIONS} ms);</li>
 *   <li>idle timeout from the last click or change of target, three times as long while the options, the prompt or the confirm view
 *       are open;</li>
 *   <li>the verdict is cached per target key; a refusal stays in the action bar for {@value #HOLD} ms, a refused typed
 *       coordinate for {@value #HOLD_TYPED} ms together with its cell (the preview outlines that cell in red meanwhile).</li>
 * </ul>
 * Server thread. Core-internal.
 */
public final class WorldPickState {
    public enum Phase { CHOOSING, OPTIONS, PROMPT, CONFIRM }

    static final long GRACE = 500, DEBOUNCE = 200, DROP = 150, SNEAK_AFTER_OPTIONS = 500, HOLD = 3000, HOLD_TYPED = 6000;

    private final long timeoutMs;
    private Phase phase = Phase.CHOOSING;
    private long lastActivity;
    private long graceUntil;
    private long lastTrigger = Long.MIN_VALUE / 2;
    private long lastDrop = Long.MIN_VALUE / 2;
    private long sneakBlockedUntil;
    private long holdUntil;
    private Object held;
    private Object heldTarget;
    private String targetKey;
    private PickVerdict cached;

    public WorldPickState(long now, long timeoutMs) {
        this.timeoutMs = Math.max(1, timeoutMs);
        this.lastActivity = now;
        this.graceUntil = now + GRACE;
    }

    public Phase phase() { return phase; }

    /** A click-like event (interact, entity use or hit, arm swing): true when it counts as a choice. */
    public boolean trigger(long now, boolean armSwing) {
        if (phase != Phase.CHOOSING || now < graceUntil) return false;
        if (armSwing && now - lastDrop < DROP) return false;
        if (now - lastTrigger < DEBOUNCE) return false;
        lastTrigger = now;
        lastActivity = now;
        return true;
    }

    /** The player dropped an item: the arm swing that follows is not a click. */
    public void dropped(long now) { lastDrop = now; }

    /** The player started sneaking: true when that opens the options. */
    public boolean sneak(long now) {
        if (phase != Phase.CHOOSING || now < graceUntil || now < sneakBlockedUntil) return false;
        lastActivity = now;
        return true;
    }

    public void openOptions(long now) { phase = Phase.OPTIONS; lastActivity = now; }

    public void prompt(long now) { phase = Phase.PROMPT; lastActivity = now; }

    /** The confirm view of an area pick is open: the pick waits as in the options. */
    public void openConfirm(long now) { phase = Phase.CONFIRM; lastActivity = now; }

    /** Options or prompt closed: back to choosing, with a short grace for the click or sneak that closed them. */
    public void resume(long now) {
        phase = Phase.CHOOSING;
        lastActivity = now;
        graceUntil = Math.max(graceUntil, now + GRACE);
        sneakBlockedUntil = now + SNEAK_AFTER_OPTIONS;
        targetKey = null;
        cached = null;
    }

    public void touch(long now) { lastActivity = now; }

    /** Within the grace after the start or after the options closed (a closing menu may still show for a moment). */
    public boolean settling(long now) { return now < graceUntil; }

    public boolean timedOut(long now) { return now - lastActivity > timeoutMs * (phase == Phase.CHOOSING ? 1 : 3); }

    /** Share of the idle time left, 0..1 (boss bar). */
    public double left(long now) {
        long span = timeoutMs * (phase == Phase.CHOOSING ? 1 : 3);
        return Math.max(0, Math.min(1, 1 - (double) (now - lastActivity) / span));
    }

    /** The aimed target's key; a new one drops the cached verdict and counts as activity. True when it changed. */
    public boolean aim(String key, long now) {
        if (key.equals(targetKey)) return false;
        targetKey = key;
        cached = null;
        lastActivity = now;
        return true;
    }

    public PickVerdict cached() { return cached; }

    public void cache(PickVerdict verdict) { cached = verdict; }

    /** Keep a refusal (handler error, denied choice) in the action bar for a while. */
    public void hold(Object message, long now) { hold(message, now, HOLD, null); }

    /** Keep a refusal for {@code millis}, with what was refused ({@code target}, may be null) for the preview. */
    public void hold(Object message, long now, long millis, Object target) { held = message; heldTarget = target; holdUntil = now + millis; }

    /** The held message, or null when there is none or it ran out. */
    public Object held(long now) { return now < holdUntil ? held : null; }

    /** What the held refusal was about (a typed cell), or null when there is none or it ran out. */
    public Object heldTarget(long now) { return now < holdUntil ? heldTarget : null; }
}
