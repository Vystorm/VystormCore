package Vystorm.vystorm_core.picker;

/**
 * Answer of a pick rule (0.71.0): {@link #ok()}, or {@link #deny(Object)} with a reason the player sees (String,
 * {@code Lang.ref} or Adventure {@code Component}). A denied row stays visible, greyed, and cannot be chosen.
 *
 * @since 0.71.0
 */
public sealed interface PickVerdict permits PickVerdict.Ok, PickVerdict.Deny {
    /** Allowed. */
    record Ok() implements PickVerdict {}

    /** Not allowed, with the reason shown to the player. */
    record Deny(Object reason) implements PickVerdict {
        public Deny { reason = reason == null ? "" : reason; }
    }

    Ok OK = new Ok();

    static PickVerdict ok() { return OK; }

    static PickVerdict deny(Object reason) { return new Deny(reason); }

    /** Whether the target may be chosen. */
    default boolean allowed() { return this instanceof Ok; }

    /** The reason of a denial; empty text for {@link #ok()}. */
    default Object reason() { return this instanceof Deny d ? d.reason : ""; }
}
