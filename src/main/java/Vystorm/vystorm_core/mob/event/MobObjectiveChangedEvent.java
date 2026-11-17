package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Objective eines Mobs hinzugefügt ({@code added}) oder entfernt. */
public class MobObjectiveChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final Objective objective;
    private final boolean added;

    public MobObjectiveChangedEvent(MobHandle mob, Objective objective, boolean added) {
        this.mob = mob;
        this.objective = objective;
        this.added = added;
    }

    public MobHandle mob() { return mob; }

    public Objective objective() { return objective; }

    public boolean added() { return added; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
