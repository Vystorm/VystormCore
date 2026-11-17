package Vystorm.vystorm_core.worlds;

/**
 * Schutz einer Plugin-Welt, den Core selbst durchsetzt (für alle Spieler in der Welt; Ausnahme: Admins im Kreativ- oder
 * Zuschauermodus mit {@link WorldSpec#adminPermission()} und ohne laufenden Besuch).
 *
 * @since 0.26.0
 */
public enum Protection {
    /** Blöcke abbauen/setzen, Eimer, Rahmen und Gemälde setzen. */
    EDITS,
    /** Kreaturen-Spawns (jeder Grund). */
    MOB_SPAWNS,
    /** Regen und Gewitter. */
    WEATHER,
    /** Zeitsprünge (Schlafen, Befehle über {@code TimeSkipEvent}). */
    TIME_SKIP,
    /** Jeder Schaden an Entities in der Welt. */
    DAMAGE,
    /** Hunger. */
    HUNGER,
    /** Portale und Endtransitportale führen nicht hinaus; Besucher verlassen die Welt stattdessen regulär. */
    PORTALS,
    /** Geschosse. */
    PROJECTILES,
    /** Rechtsklick auf Entities, Rüstungsständer. */
    ENTITY_INTERACT,
    /** Items aufheben. */
    ITEM_PICKUP
}
