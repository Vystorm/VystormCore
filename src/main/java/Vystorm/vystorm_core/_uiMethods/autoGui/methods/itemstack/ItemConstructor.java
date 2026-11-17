package Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods.description;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods.enchant;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods.nbt;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.lowerMethods.title;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

public class ItemConstructor {
private String name;
private ArrayList<String> lore;
private String permission;
private String function;
private Material material;
private String NBT;
private boolean enchanted;
private String path;

//lower methods only one method they are in their own class for better readability
description description = new description();
enchant enchant = new enchant();
nbt nbt = new nbt();
title title = new title();
private String functionValue;


public void setName(String name) {
	    this.name = name;
}

public void setLore(ArrayList<String> lore) {
	    this.lore = lore;
}

public void addLore(String loreLine) {
	    if (this.lore == null) {
			  this.lore = new ArrayList<String>();
	    }
	    this.lore.add(loreLine);
}

public void setPermission(String permission) {
	    this.permission = permission;
}

public void setFunction(String function, String value) {
	    this.function = function;
	    this.functionValue = value;}

public void setPath (String path) {
	    this.path = path;
}

public void setMaterial(Material item) {
	    this.material = item;
}

public void setMaterial(String item) {
	    try {
			  this.material = Material.valueOf(item);
	    } catch (Exception e) {
			  DebugMessages.mid("[ItemConstructor] Unknown item material; using COMMAND_BLOCK");
              this.material = Material.COMMAND_BLOCK;
	    }
}

public void setNBT(String NBT) {
	    this.NBT = NBT;
}

public void setEnchanted(boolean enchanted) {
	    this.enchanted = enchanted;
}

public ItemStack build() {
	    ItemStack item;
	    if (material != null) {item = new ItemStack(material);}else {item = new ItemStack(Material.COMMAND_BLOCK);}
	    title.set(name, item);
	    description.set(lore, item);
	    nbt.set(item , function, functionValue);
	    nbt.set(item , "permission", permission);
	    nbt.set(item, "path" , path);

        DebugMessages.low("[ItemConstructor] GUI item built; material=" + item.getType());

	    return item;
}

}
