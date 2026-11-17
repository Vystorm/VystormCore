package Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods;

import Vystorm.vystorm_core.main;
import org.bukkit.inventory.ItemStack;

public class nbt {
 	public ItemStack set(ItemStack target, String key, String value) {
		     if (key != null) main.getApi().inventoryInteractor().setNBTforItem(target, key, value);
		     return target;
	}
}
