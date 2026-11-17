package Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class title {
  public ItemStack set(String title, ItemStack target) {
		ItemMeta meta = target.getItemMeta();
		assert meta != null;
		meta.setDisplayName(title);
		target.setItemMeta(meta);
        DebugMessages.low("[title] Item title metadata updated");
		return target;
  }
}
