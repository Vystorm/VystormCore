package Vystorm.vystorm_core.worlds;

/**
 * Warum ein Besuch endet. Neue Werte können dazukommen – Konsumenten brauchen in {@code switch} einen {@code default}.
 *
 * @since 0.26.0
 */
public enum ExitReason {
    /** Das Plugin beendet den Besuch (Werkzeug, eigener Ausgang). */
    PLUGIN,
    /** Der Spieler hat einen Befehl benutzt. */
    COMMAND,
    /** Portal in der Welt. */
    PORTAL,
    /** Spieler verlässt den Server. */
    QUIT,
    /** Plugin oder Server wird abgeschaltet. */
    SHUTDOWN,
    /** Ein fremder Teleport hat den Spieler schon aus der Welt gebracht. */
    WORLD_CHANGE,
    /** Die Welt wird neu aufgebaut. */
    REBUILD,
    /** Ein Admin hat den Besuch beendet. */
    ADMIN,
    /** Der Teleport hinein ist gescheitert. */
    FAILED_ENTRY
}
