package Vystorm.vystorm_core.mob.extension;

import java.util.UUID;
import org.bukkit.entity.Entity;

/** Empfänger für eigene Trigger/Signale (implementiert von Vystorm-Mobs). */
@FunctionalInterface
public interface TriggerSink {
    void fire(UUID mobId, String trigger, Entity cause);
}
