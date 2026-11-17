package Vystorm.vystorm_core.mob;

import java.util.Optional;

/**
 * Liefert Beziehungen für einen Teilbereich (Arena-Teams, Städte, Fraktionen, Pets …). Leeres Ergebnis heißt
 * "keine Aussage"; der nächste Provider nach Priorität entscheidet. Muss schnell und ohne Weltzugriff sein.
 */
public interface RelationProvider {
    String id();

    int priority();

    Optional<Relation> relation(EntityRef source, EntityRef target);
}
