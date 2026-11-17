package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: eine Definition wurde (neu) registriert. Server-Thread. */
public class MobRegisteredEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobDefinitionView definition;

    public MobRegisteredEvent(MobDefinitionView definition) {
        this.definition = definition;
    }

    public MobDefinitionView definition() { return definition; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
