package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Mob wurde entfernt (nicht durch Tod). */
public class MobDespawnedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final DespawnReason reason;

    public MobDespawnedEvent(MobHandle mob, DespawnReason reason) {
        this.mob = mob;
        this.reason = reason;
    }

    public MobHandle mob() { return mob; }

    public DespawnReason reason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
