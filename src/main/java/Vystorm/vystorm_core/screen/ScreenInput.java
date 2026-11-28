package Vystorm.vystorm_core.screen;

/** Input delivered to a screen's handler (since 0.57.0). Coordinates are centre-origin panel units (u right, v up). */
public sealed interface ScreenInput {
    enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK }
    enum Reason { PLAYER, DAMAGE, TELEPORT, WORLD, QUIT, DEATH, PLUGIN, COMMAND }

    /** A click; {@code widgetId} is null when no button or input was under the pointer. */
    record Click(String widgetId, double u, double v, boolean right) implements ScreenInput {}
    /** The pointer moved onto another button or input ({@code widgetId}), or off all of them (null). */
    record Hover(String widgetId) implements ScreenInput {}
    /**
     * Mouse wheel through the hotbar: -1 or +1 per notch (positive: wheel down). Since 0.66.2 the notches of one server
     * tick come added up in one Scroll (at most 4 either way), so treat {@code delta} as a number of steps.
     */
    record Scroll(int delta) implements ScreenInput {}
    /** A movement key claimed by the screen (see {@link ScreenSpec.Builder#claim}); only on servers with input events. */
    record KeyInput(Key key, boolean pressed) implements ScreenInput {}
    /** The screen closed; always the last input of a session. */
    record Closed(Reason reason) implements ScreenInput {}
    /**
     * Where the pointer is (since 0.72.0, capability {@code screen-pointer}): at most one per server tick while the pointer is over
     * a widget the screen asked samples for with {@link ScreenSession#trackPointer}, and only when it moved since the
     * last sample. {@code (u, v)} are centre-origin panel units like {@link Click}, so {@code mapPixel(u, v, ..)}
     * gives the canvas pixel. Leaving the widget comes as a {@link Hover} to another widget or null. Never sent to a
     * screen that did not ask, so existing handlers see no new input type.
     */
    record Pointer(String widgetId, double u, double v) implements ScreenInput {}
    /**
     * A mouse button went down or up (since 0.72.0, capability {@code screen-press}), sent only to a screen that asked with
     * {@link ScreenSession#trackPress}. Right button only ({@code right} is always true for now): {@code down} comes
     * right before the {@link Click} of the first "use" of a hold, {@code up} once no repeat arrived for
     * {@code Screens.PRESS_RELEASE_MILLIS} (a held right button repeats every 4 client ticks, 200 ms), and also before
     * {@link Closed} when the screen closes while the button is down. The left button sends one {@link Click} on
     * button down and nothing while held or on release, so it has no Press.
     */
    record Press(boolean right, boolean down) implements ScreenInput {}
}
