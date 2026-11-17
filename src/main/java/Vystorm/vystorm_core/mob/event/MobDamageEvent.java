package Vystorm.vystorm_core.mob.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Schaden oder Heilung aus Mob-Fähigkeiten. {@link Stage#PRE} ist veränderbar/cancellable und kommt vor der
 * finalen Berechnung, {@link Stage#POST} informiert über den angewandten Wert.
 */
public class MobDamageEvent extends Event implements Cancellable {
    public enum Stage { PRE, POST }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Stage stage;
    private final UUID source;
    private final UUID target;
    private final String damageType;
    private final String abilityId;
    private final boolean heal;
    private double amount;
    private boolean cancelled;

    public MobDamageEvent(Stage stage, UUID source, UUID target, String damageType, String abilityId, boolean heal, double amount) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.source = source;
        this.target = target;
        this.damageType = damageType;
        this.abilityId = abilityId;
        this.heal = heal;
        this.amount = amount;
    }

    public Stage stage() { return stage; }
    public UUID source() { return source; }
    public UUID target() { return target; }
    public String damageType() { return damageType; }
    public String abilityId() { return abilityId; }
    public boolean heal() { return heal; }
    public double amount() { return amount; }

    public void amount(double value) {
        if (stage != Stage.PRE) throw new IllegalStateException("POST damage is informative");
        amount = Math.max(0, value);
    }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) {
        if (stage == Stage.PRE) cancelled = cancel;
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
