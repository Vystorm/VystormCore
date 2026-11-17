package Vystorm.vystorm_core.worlds;

/**
 * Core hat das Laden, Neuaufbauen oder Entladen einer Plugin-Welt verweigert oder es ist gescheitert. Die Welt ist dann
 * nicht registriert; am Ordner wurde nichts gelöscht, außer bei {@link Reason#WIPE_FAILED} (teilweise).
 *
 * @since 0.26.0
 */
public final class ManagedWorldException extends RuntimeException {
    public enum Reason {
        /** ID schon von einer anderen Registrierung belegt. */
        ID_TAKEN,
        /** Eine Welt mit diesem Namen ist schon geladen (nicht von Core verwaltet). */
        ALREADY_LOADED,
        /** Ordner hat Chunk-Daten ohne Markierung oder mit fremder ID. */
        FOREIGN_FOLDER,
        /** Chunk-Daten ließen sich nicht vollständig löschen. */
        WIPE_FAILED,
        /** Bukkit hat die Welt nicht angelegt oder nicht entladen. */
        BUKKIT_FAILED,
        /** Aufruf nicht vom Server-Thread oder vom falschen Besitzer. */
        ILLEGAL_CALL
    }

    private final Reason reason;

    public ManagedWorldException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
