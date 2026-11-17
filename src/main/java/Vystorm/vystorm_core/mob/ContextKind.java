package Vystorm.vystorm_core.mob;

/** Kontextebenen eines {@link MobContext}; mehrere dürfen gleichzeitig aktiv sein (z. B. CITY + EVENT). */
public enum ContextKind { WORLD, REGION, ARENA, CITY, FACTION, EVENT, DUNGEON }
