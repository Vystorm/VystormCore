package Vystorm.vystorm_core.worldevent.engine;

/** Optional integration hook for SpawnDirector, Cities, Factions and other plugins. */
public interface EventLifecycleListener {
    String id();

    default void onStarted(ActiveEvent event) {}

    default void onEnded(ActiveEvent event, EventEndReason reason) {}
}
