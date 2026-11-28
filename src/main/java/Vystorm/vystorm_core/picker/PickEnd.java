package Vystorm.vystorm_core.picker;

/**
 * How a pick flow ended (0.71.0). Only an end the player asked for ({@link #DONE}, {@link #BACK}) takes the player back
 * to the calling menu; anything the game or another plugin caused leaves the player where they are.
 *
 * @since 0.71.0
 */
public enum PickEnd {
    /** The pick handler returned ok; the calling menu opens again with its message. */
    DONE,
    /** Back or Cancel, ESC or sneak in the picker; the calling menu opens again. */
    BACK,
    /** No click for longer than the pick's timeout; the player gets a chat line with "Open again". */
    TIMEOUT,
    /** The game closed the picker: damage, a teleport by another plugin, death. */
    INTERRUPTED,
    /** The player changed worlds during a pick that needs one world. */
    WORLD_CHANGED,
    /** Another flow started, or another plugin opened an inventory or a screen for the player. */
    REPLACED,
    /** The player left the server. */
    QUIT,
    /** The plugin that started the flow was switched off. */
    OWNER_DISABLED,
    /** {@code pickers().cancel(player)}. */
    CANCELLED_BY_PLUGIN
}
