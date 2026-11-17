package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Event-Listener für EntityDamageEvent.
 * Leitet Entity-Damage-Events an das Prioritätssystem für NBT-Verarbeitung weiter.
 */
public class EntityDamageListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        main.getApi().priority().entityDamageListener(event);
    }
}

