package Vystorm.vystorm_core.blocks;

/**
 * Why the cells of a {@link BlocksEditedEvent} changed.
 *
 * @since 0.69.0
 */
public enum EditCause {
    /** A new edit (set, replace, walls, ...). */
    EDIT,
    /** An earlier edit was undone: the cells went back to their state before it. */
    UNDO,
    /** An undone edit was done again. */
    REDO,
    /** A paste of a copied or loaded structure. */
    PASTE,
    /** Anything else (generators, restores, plugins without an edit history). */
    OTHER
}
