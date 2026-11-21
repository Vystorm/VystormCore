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
}
