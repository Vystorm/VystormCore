package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Mob wurde gespawnt und ist initialisiert. */
public class MobSpawnedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final MobSpawnRequest request;

    public MobSpawnedEvent(MobHandle mob, MobSpawnRequest request) {
        this.mob = mob;
        this.request = request;
    }

    public MobHandle mob() { return mob; }

    public MobSpawnRequest request() { return request; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
