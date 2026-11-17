package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPickupItemEvent;

/**
 * Leerer Listener für das veraltete PlayerPickupItemEvent.
 * @deprecated Wird seit 0.11.4 nicht mehr registriert; bleibt nur aus Kompatibilitätsgründen bestehen.
 */
@Deprecated
public class PlayerPickupItemListener implements Listener {

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {

    }
}

