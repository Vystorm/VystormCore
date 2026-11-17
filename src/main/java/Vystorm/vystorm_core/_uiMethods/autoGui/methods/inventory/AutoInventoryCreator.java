package Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;

public class AutoInventoryCreator {
	public Inventory create(String name, int i){
		Inventory inv = Bukkit.createInventory(null, 54, name);
        DebugMessages.low("[AutoInventoryCreator] Inventory created; title=" + name + "; size=" + inv.getSize());

	return inv;
	}
}
