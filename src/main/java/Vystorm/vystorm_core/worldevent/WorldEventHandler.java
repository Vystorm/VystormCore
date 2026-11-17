package Vystorm.vystorm_core.worldevent;

import Vystorm.vystorm_core.worldevent.engine.ActiveEvent;
import Vystorm.vystorm_core.worldevent.engine.EventEndReason;

/** Main-thread execution supplied by a content plugin. Use instanceId for idempotent recovery. */
public interface WorldEventHandler {
    void start(ActiveEvent event);
    default void resume(ActiveEvent event) { }
    void stop(ActiveEvent event, EventEndReason reason);
}
