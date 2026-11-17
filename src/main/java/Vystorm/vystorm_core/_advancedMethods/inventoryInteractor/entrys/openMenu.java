package Vystorm.vystorm_core._advancedMethods.inventoryInteractor.entrys;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._advancedMethods.inventoryInteractor.InteractorEntry;
import Vystorm.vystorm_core.main;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryDragEvent;


public class openMenu implements InteractorEntry {
@Override
public String getNBT() {
     return "openMenu";
}

@Override
public void leftClick(Player player, Event event, String value) {
     if (event instanceof org.bukkit.event.inventory.InventoryClickEvent clickEvent) {
	  clickEvent.setCancelled(true);
	  org.bukkit.Bukkit.getScheduler().runTask(main.getPlugin(), () -> {
              if (!player.isOnline() || Vystorm.vystorm_core.compat.InventoryCompat.top(player.getOpenInventory()) != Vystorm.vystorm_core.compat.InventoryCompat.top(clickEvent.getView())) return;
              if ("Plugins[0]".equals(value)) {
                  main.getApi().gui().openDirectory(player, 0);
                  return;
              }
              org.bukkit.inventory.Inventory inventory = main.getApi().gui().getInventory(value, player);
              if (inventory != null) {
                  player.openInventory(inventory);
                  DebugMessages.low("[openMenu] Interactor opened menu " + value);
              } else {
                  DebugMessages.mid("[openMenu] Menu opening skipped: unavailable or permission denied");
              }
          });
     }
}

@Override
public void rightClick(Player player, Event event, String value) {
     leftClick(player, event, value);
}

@Override
public void shiftLeftClick(Player player, Event event, String value) {
     if (event instanceof org.bukkit.event.inventory.InventoryClickEvent clickEvent) {
	  clickEvent.setCancelled(true);
     }
}

@Override
public void shiftRightClick(Player player, Event event, String value) {
     if (event instanceof org.bukkit.event.inventory.InventoryClickEvent clickEvent) {
	  clickEvent.setCancelled(true);
     }
}

@Override
public void middleClick(Player player, Event event, String value) {
     if (event instanceof org.bukkit.event.inventory.InventoryClickEvent clickEvent) {
	  clickEvent.setCancelled(true);
     }
}

@Override
public void dropClick(Player player, Event event) {
     if (event instanceof org.bukkit.event.player.PlayerDropItemEvent dropEvent) {
	  dropEvent.setCancelled(true);
     }

}

@Override
public void dragEventOld(InventoryDragEvent event, InteractorEntry entryDrag) {
     event.setCancelled(true);
}

@Override
public void dragEventNew(InventoryDragEvent event) {
     event.setCancelled(true);
}
}
