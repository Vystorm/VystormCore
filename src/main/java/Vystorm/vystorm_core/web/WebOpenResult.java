package Vystorm.vystorm_core.web;

/**
 * What {@link WebServices#openOrLink} / {@link WebServices#openOrElse} did.
 *
 * @since 0.24.0
 */
public enum WebOpenResult {
    /** The page opens in the in-game overlay of the Vystorm client mod ({@link ClientServices#show}). */
    OVERLAY,
    /** A one-time login link was sent to the chat ({@link WebServices#sendLink}). */
    LINK,
    /** The plugin's fallback ran (e.g. its chest or dialog menu). */
    FALLBACK,
    /** Nothing was possible (no overlay, no link, no fallback); the player got Core's hint in chat. */
    NONE
}
