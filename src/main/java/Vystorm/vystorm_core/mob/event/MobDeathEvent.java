package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Mob ist gestorben ({@code killer} kann null sein). */
public class MobDeathEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final UUID killer;

    public MobDeathEvent(MobHandle mob, UUID killer) {
        this.mob = mob;
        this.killer = killer;
    }

    public MobHandle mob() { return mob; }

    public UUID killer() { return killer; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
