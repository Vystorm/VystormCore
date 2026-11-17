package Vystorm.vystorm_core._coreMethods.eventRegulator;

import org.bukkit.event.EventPriority;

/**
 * The named stages in which Core dispatches a shared Bukkit event to the handlers that plugins registered through
 * {@code api.priority().stages()}. A plugin says what its handler does; Core decides when it runs.
 * <p>
 * Stages run in the order of this enum. Each stage is bound to one Bukkit priority, so handlers of plugins that do
 * not use Core keep their usual place between the stages.
 *
 * @since 0.64.0
 */
public enum EventStage {
    /** Prepares the event for everyone else (tags, context). Must not decide whether the action is allowed. */
    PREPARE(EventPriority.LOWEST),
    /** Protection: decides whether the action is allowed at all (locks, claims, regions). Cancels to deny. */
    PROTECT(EventPriority.LOW),
    /** Changes the outcome of an allowed action (drops, damage, target) or cancels it for a gameplay rule. */
    ADJUST(EventPriority.NORMAL),
    /** The feature acts on an allowed action (vein mining, custom blocks, opening a menu). Cancels to consume it. */
    ACT(EventPriority.HIGH),
    /** Last corrections after every feature has acted. For the rare handler that must have the final word. */
    FINISH(EventPriority.HIGHEST),
    /** Reads the final outcome (rewards, statistics, logs). Core restores the cancelled state if a handler changes it. */
    OBSERVE(EventPriority.MONITOR);

    private final EventPriority bukkit;

    EventStage(EventPriority bukkit) { this.bukkit = bukkit; }

    /** The Bukkit priority at which Core's own listener dispatches this stage. */
    public EventPriority bukkit() { return bukkit; }
}
