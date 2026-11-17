package Vystorm.vystorm_core._uiMethods.autoGui;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.main;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;


public class NoPermissionInventoryStripper {
public Inventory strip(Inventory inv, Player player) {
     if (inv == null || player == null) return inv;
     for (ItemStack item : inv.getContents()) {
	  if (item == null) continue;
	  // Check if item has permission tag
	  // moved into an try and catch to prevent errors from breaking everything
	  // when an item doesn't have the expected NBT data
	  try {
	       if (main.getApi().luckperms().permissionChecker(main.getApi().inventoryInteractor().getValueForItem(item, "permission"), player)) {;
		    continue;
	       }
	  } catch (Exception e) {
	       DebugMessages.mid("[NoPermissionInventoryStripper] Error checking permissions for item in NoPermissionInventoryStripper: " + e.getMessage());
	       continue;
	  }
	  inv.remove(item);
          DebugMessages.low("[NoPermissionInventoryStripper] Removed an inventory item denied by permission check");
     }
     return inv;
}
}
