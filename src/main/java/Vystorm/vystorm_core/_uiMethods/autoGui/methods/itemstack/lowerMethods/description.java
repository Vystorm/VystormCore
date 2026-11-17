package Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;

public class description {
public ItemStack set(ArrayList<String> lore, ItemStack item) {
	ItemMeta meta = item.getItemMeta();
    assert meta != null;
    meta.setLore(lore);
    item.setItemMeta(meta);
        DebugMessages.low("[description] Item lore metadata updated");
    return item;
}

public ItemStack add(String loreLine, ItemStack item) {
	    ItemMeta meta = item.getItemMeta();
    assert meta != null;
    ArrayList<String> lore = new ArrayList<>();
    if (meta.hasLore()) {
		  lore = (ArrayList<String>) meta.getLore();
    }
    lore.add(loreLine);
    meta.setLore(lore);
    item.setItemMeta(meta);
        DebugMessages.low("[description] Item lore metadata updated");
    return item;
}
}
