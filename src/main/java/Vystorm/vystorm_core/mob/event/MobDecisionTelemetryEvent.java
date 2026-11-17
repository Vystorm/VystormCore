package Vystorm.vystorm_core.mob.event;

import Vystorm.vystorm_core.mob.*;
import java.util.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Asynchrone Telemetrie: letzte AI-Entscheidung eines Mobs. Nur lesen, keine Weltzugriffe. */
public class MobDecisionTelemetryEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID mobId;
    private final String definitionId;
    private final String action;
    private final double score;
    private final Map<String, Double> scores;

    public MobDecisionTelemetryEvent(UUID mobId, String definitionId, String action, double score, Map<String, Double> scores) {
        super(true);
        this.mobId = mobId;
        this.definitionId = definitionId;
        this.action = action;
        this.score = score;
        this.scores = scores;
    }

    public UUID mobId() { return mobId; }

    public String definitionId() { return definitionId; }

    public String action() { return action; }

    public double score() { return score; }

    public Map<String, Double> scores() { return scores; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
