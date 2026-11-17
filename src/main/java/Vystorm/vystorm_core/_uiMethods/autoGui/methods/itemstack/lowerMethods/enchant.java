package Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class enchant {
	public ItemStack set(String enchant, int level, ItemStack target) {
		ItemMeta meta = target.getItemMeta();
		assert meta != null;
		meta.setEnchantmentGlintOverride( true);
		target.setItemMeta(meta);
        DebugMessages.low("[enchant] Item enchantment glint enabled");
		return target;
	}
}
