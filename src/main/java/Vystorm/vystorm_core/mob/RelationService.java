package Vystorm.vystorm_core.mob;

/** Zentrale Relationsebene; alle Target-Entscheidungen laufen hierüber. */
public interface RelationService {
    Relation getRelation(EntityRef source, EntityRef target);

    default boolean canAttack(EntityRef source, EntityRef target) {
        return !source.id().equals(target.id()) && getRelation(source, target).attackable();
    }

    default boolean canAssist(EntityRef source, EntityRef target) {
        return source.id().equals(target.id()) || getRelation(source, target).assistable();
    }
}
