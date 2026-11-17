package Vystorm.vystorm_core.bundleclasses.baseListener;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.event.*;
import java.util.Objects;

/** Optional Paper signal; Bukkit inventory, interaction and entity pickup events remain in the common listener. */
public final class PaperInventoryEventListener implements Listener {
    @EventHandler(priority=EventPriority.MONITOR)
    public void slotChange(PlayerInventorySlotChangeEvent event) {
        if (!Objects.equals(event.getOldItemStack(),event.getNewItemStack()))
            Vystorm.vystorm_core.main.getApi().inventoryEvents().changed(event.getPlayer());
    }
}
