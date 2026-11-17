package Vystorm.vystorm_core.worlds;

/**
 * Ergebnis von {@link VisitService#enter}. Nur {@link #OK} startet einen Besuch; bei allen anderen Werten ist am Spieler
 * nichts verändert. Neue Werte können dazukommen – Konsumenten brauchen in {@code switch} einen {@code default}.
 *
 * @since 0.26.0
 */
public enum VisitResult {
    OK,
    /** Welt nicht registriert oder nicht geladen. */
    NO_WORLD,
    /** Ankunftsort liegt nicht in dieser Welt. */
    WRONG_TARGET,
    OFFLINE,
    /** Schon in einem Besuch oder in einer Plugin-Welt. */
    ALREADY,
    /** Ein Schnappschuss oder Besuchs-Marker ist noch offen (Wiederherstellung steht aus). */
    PENDING_RECOVERY,
    DEAD,
    VEHICLE,
    GLIDING,
    SLEEPING,
    /** {@link VisitPolicy#cooldownSeconds()} noch nicht abgelaufen. */
    COOLDOWN,
    /** Spieler steht in einer gesperrten Regionsart, oder die Regionsprüfung ist fehlgeschlagen (fail-closed). */
    BLOCKED_REGION,
    /** Schnappschuss ließ sich nicht schreiben. */
    SNAPSHOT_FAILED;

    public boolean ok() { return this == OK; }
}
