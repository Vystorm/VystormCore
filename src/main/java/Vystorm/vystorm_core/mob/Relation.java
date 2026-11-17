package Vystorm.vystorm_core.mob;

/** Beziehung einer Quelle zu einem Ziel, geordnet von freundlich nach feindlich. */
public enum Relation {
    ALLY,
    FRIENDLY,
    NEUTRAL,
    UNWELCOME,
    HOSTILE,
    INVADER;

    public boolean attackable() { return this == HOSTILE || this == INVADER; }

    public boolean assistable() { return this == ALLY || this == FRIENDLY; }
}
