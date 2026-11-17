package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Event-Listener für EntityDeathEvent.
 * Leitet Entity-Death-Events an das Prioritätssystem für NBT-Verarbeitung weiter.
 */
public class EntityDeathListener implements Listener {

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event){
        main.getApi().priority().entityDeathListener(event);
    }

}

