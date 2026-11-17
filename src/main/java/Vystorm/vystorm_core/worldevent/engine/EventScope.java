package Vystorm.vystorm_core.worldevent.engine;

public enum EventScope {
    GLOBAL,
    /** Eine Welt; scopeKey ist Welt-UUID oder -Name. */
    WORLD,
    REGIONAL,
    LOCAL
}
