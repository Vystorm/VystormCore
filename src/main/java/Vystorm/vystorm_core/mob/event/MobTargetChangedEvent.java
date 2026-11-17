package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable: Mob wechselt sein Ziel ({@code null} = kein Ziel). */
public class MobTargetChangedEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final UUID previousTarget;
    private final UUID newTarget;
    private boolean cancelled;

    public MobTargetChangedEvent(MobHandle mob, UUID previousTarget, UUID newTarget) {
        this.mob = mob;
        this.previousTarget = previousTarget;
        this.newTarget = newTarget;
    }

    public MobHandle mob() { return mob; }

    public UUID previousTarget() { return previousTarget; }

    public UUID newTarget() { return newTarget; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
