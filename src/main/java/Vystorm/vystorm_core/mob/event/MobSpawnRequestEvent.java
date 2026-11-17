package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.MobSpawnRequest;
import java.util.Objects;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable: Spawn-Auftrag vor Ausführung; Listener dürfen den Auftrag ersetzen. */
public class MobSpawnRequestEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private MobSpawnRequest request;
    private boolean cancelled;

    public MobSpawnRequestEvent(MobSpawnRequest request) { this.request = Objects.requireNonNull(request, "request"); }

    public MobSpawnRequest request() { return request; }

    public void request(MobSpawnRequest replacement) { request = Objects.requireNonNull(replacement, "request"); }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
