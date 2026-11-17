package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Informativ: Boss-Encounter-Ereignis (START, PHASE, ENRAGE, RESET, KILL, WIPE). */
public class MobBossEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MobHandle boss;
    private final String stage;
    private final int phase;

    public MobBossEvent(MobHandle boss, String stage, int phase) {
        this.boss = boss;
        this.stage = stage;
        this.phase = phase;
    }

    public MobHandle boss() { return boss; }

    public String stage() { return stage; }

    public int phase() { return phase; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
