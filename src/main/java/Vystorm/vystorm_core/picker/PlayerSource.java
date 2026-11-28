package Vystorm.vystorm_core.picker;

/**
 * Where the rows of a player picker come from; each one is a tab, in the order the pick lists them (0.71.0).
 *
 * @since 0.71.0
 */
public enum PlayerSource {
    /** Players online now whom the viewer can see (vanished players never show as online). */
    ONLINE,
    /** Online players in the viewer's world within the pick's radius, nearest first. */
    NEARBY,
    /** Players seen within the pick's recent days who are offline now, newest first (from the known-player store). */
    RECENT,
    /** A name search: online players and known players whose name starts with what the viewer typed. */
    SEARCH
}
