package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickEnd;

/**
 * The end rules of a world pick (design 2.3 and 4.7, owner decision 6) as one table: what happened, how the pick ends,
 * whether the player gets the "Open again" chat line and whether the player goes back to the calling menu. Only an end
 * the player asked for (a choice, Cancel) goes back; anything the game or another plugin caused leaves the player in
 * the world, with "Open again" when the player would otherwise not know why the pick stopped. Damage, a teleport
 * within the world and closing the area confirm view keep the pick running. Pure. Core-internal.
 */
public final class WorldPickEnds {
    private WorldPickEnds() {}

    public enum Cause {
        /** The handler returned ok. */
        CHOSEN,
        /** The handler threw: back with {@code panel.failed}. */
        HANDLER_FAILED,
        /** Cancel in the options, the confirm view or the chat line. */
        CANCEL_BUTTON,
        /** No click and no change of target for the timeout. */
        TIMEOUT,
        DEATH,
        WORLD_CHANGE,
        /** Another plugin opened an inventory or a screen. */
        OTHER_MENU,
        /** Another pick started for the player. */
        NEW_PICK,
        /** The handler itself started another pick: this one is done, the new one takes over. */
        NEW_PICK_FROM_HANDLER,
        QUIT,
        OWNER_DISABLED,
        /** {@code pickers().cancel(..)} or {@code PickFlow.cancel()}. */
        PLUGIN_CANCEL,
        DAMAGE,
        TELEPORT_SAME_WORLD,
        /** ESC on the area confirm view: both corners are kept, a click or sneak opens it again. */
        CONFIRM_CLOSED
    }

    /** {@code end} null = the pick keeps running. */
    public record Rule(PickEnd end, boolean openAgain, boolean goBack) {}

    private static final Rule KEEP = new Rule(null, false, false);

    public static Rule rule(Cause cause) {
        return switch (cause) {
            case CHOSEN, HANDLER_FAILED -> new Rule(PickEnd.DONE, false, true);
            case CANCEL_BUTTON -> new Rule(PickEnd.BACK, false, true);
            case TIMEOUT -> new Rule(PickEnd.TIMEOUT, true, false);
            case DEATH -> new Rule(PickEnd.INTERRUPTED, true, false);
            case WORLD_CHANGE -> new Rule(PickEnd.WORLD_CHANGED, true, false);
            case OTHER_MENU -> new Rule(PickEnd.REPLACED, true, false);
            case NEW_PICK -> new Rule(PickEnd.REPLACED, false, false);
            case NEW_PICK_FROM_HANDLER -> new Rule(PickEnd.DONE, false, false);
            case QUIT -> new Rule(PickEnd.QUIT, false, false);
            case OWNER_DISABLED -> new Rule(PickEnd.OWNER_DISABLED, false, false);
            case PLUGIN_CANCEL -> new Rule(PickEnd.CANCELLED_BY_PLUGIN, false, false);
            case DAMAGE, TELEPORT_SAME_WORLD, CONFIRM_CLOSED -> KEEP;
        };
    }
}
