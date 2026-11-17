package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Mob wurde persistent (Nemesis-Hook). */
public class MobPromotedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final UUID persistentId;

    public MobPromotedEvent(MobHandle mob, UUID persistentId) {
        this.mob = mob;
        this.persistentId = persistentId;
    }

    public MobHandle mob() { return mob; }

    public UUID persistentId() { return persistentId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
