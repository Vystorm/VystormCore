package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable: Squad-Befehl an eine Gruppe. */
public class MobSquadOrderEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobGroupHandle group;
    private final String order;
    private final Objective objective;
    private boolean cancelled;

    public MobSquadOrderEvent(MobGroupHandle group, String order, Objective objective) {
        this.group = group;
        this.order = order;
        this.objective = objective;
    }

    public MobGroupHandle group() { return group; }

    public String order() { return order; }

    public Objective objective() { return objective; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
