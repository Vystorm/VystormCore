package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable: Mob beginnt eine Fähigkeit zu wirken. */
public class MobAbilityCastEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle mob;
    private final String abilityId;
    private final List<UUID> targets;
    private boolean cancelled;

    public MobAbilityCastEvent(MobHandle mob, String abilityId, List<UUID> targets) {
        this.mob = mob;
        this.abilityId = abilityId;
        this.targets = targets;
    }

    public MobHandle mob() { return mob; }

    public String abilityId() { return abilityId; }

    public List<UUID> targets() { return targets; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
