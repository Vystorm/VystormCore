package Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.ItemConstructor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;


import static Vystorm.vystorm_core.main.getApi;
import static Vystorm.vystorm_core.main.getPlugin;

public class SetMenuBar {
Inventory target;
API api;
FileConfiguration settings = null;


	@SuppressWarnings("deprecation")
	public Inventory setMenu(Inventory inventory){
		    target = inventory;
		    api = getApi();
		    // Optional: plugins/vystorm_core/settings/menubar.yaml überschreibt die eingebauten Buttons.
		    settings = null;
		    try {
				  var entry = api.data().dataEntry(getPlugin(), "settings", null, "menubar.yaml");
				  settings = entry == null ? null : entry.getEntry();
		    } catch (RuntimeException failure) {
				  DebugMessages.low("[SetMenuBar] menubar.yaml not available; using default buttons (" + failure.getClass().getSimpleName() + ")");
		    }

		    setBack();
		    setForwards();
		    setClose();
		    setMenu();
		    SetEdit();
		    setPlaceholder();
		    return target;
	}
//45-53

	private void setBack(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("back.enabled") == false) return;
				  creator.setMaterial(settings.getString("back.material"));
				  creator.setName(settings.getString("back.name"));
				  settings.getStringList("back.lore").forEach(creator::addLore);
				  creator.setEnchanted(true);
				  creator.addLore("Function: back");
				  target.setItem(settings.getInt("back.slot"), creator.build());
		    } else {
				  DebugMessages.low("[SetMenuBar] MenuBar configuration absent; using default buttons");
				  creator.setMaterial("ARROW");
				  creator.setName("&cBack");
				  creator.addLore("&7Go to the previous page");
				  creator.setEnchanted(true);
				  creator.addLore("Function: back");
				  target.setItem(45, creator.build());
		    }

	}
	private void setForwards(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("forwards.enabled") == false) return;
				  creator.setMaterial(settings.getString("forwards.material"));
				  creator.setName(settings.getString("forwards.name"));
				  settings.getStringList("forwards.lore").forEach(creator::addLore);
				  creator.setEnchanted(true);
				  creator.addLore("Function: forwards");
				  target.setItem(settings.getInt("forwards.slot"), creator.build());
		    } else {
				  creator.setMaterial("ARROW");
				  creator.setName("&cForwards");
				  creator.addLore("&7Go to the next page");
				  creator.setEnchanted(true);
				  creator.addLore("Function: forwards");
				  target.setItem(53, creator.build());
		    }

	}
	private void setClose(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("close.enabled") == false) return;
				  creator.setMaterial(settings.getString("close.material"));
				  creator.setName(settings.getString("close.name"));
				  settings.getStringList("close.lore").forEach(creator::addLore);
				  creator.setEnchanted(true);
				  creator.addLore("Function: close");
				  target.setItem(settings.getInt("close.slot"), creator.build());
		    } else {
				  creator.setMaterial("BARRIER");
				  creator.setName("&cClose");
				  creator.addLore("&7Close the inventory");
				  creator.setEnchanted(true);
				  creator.addLore("Function: close");
				  target.setItem(49, creator.build());
		    }

	}
	//sets the spot for the menu button
	private void setMenu(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("menu.enabled") == false) return;
				  creator.setMaterial(settings.getString("menu.material"));
				  creator.setName(settings.getString("menu.name"));
				  settings.getStringList("menu.lore").forEach(creator::addLore);
				  creator.setEnchanted(true);
				  creator.addLore("Function: menu");
				  target.setItem(settings.getInt("menu.slot"), creator.build());
		    } else {
				  creator.setMaterial("COMPASS");
				  creator.setName("&cMenu");
				  creator.addLore("&7Open the main menu");
				  creator.setEnchanted(true);
				  creator.addLore("Function: menu");
				  target.setItem(48, creator.build());
		    }

	}
	private void SetEdit(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("edit.enabled") == false) return;
				  creator.setMaterial(settings.getString("edit.material"));
				  creator.setName(settings.getString("edit.name"));
				  settings.getStringList("edit.lore").forEach(creator::addLore);
				  creator.setEnchanted(true);
				  creator.addLore("Function: edit");
				  target.setItem(settings.getInt("edit.slot"), creator.build());
		    } else {
				  creator.setMaterial("PAPER");
				  creator.setName("&cEdit");
				  creator.addLore("&7Edit the inventory");
				  creator.setEnchanted(true);
				  creator.addLore("Function: edit");
				  target.setItem(50, creator.build());
		    }

	}
	private void setPlaceholder(){
		    ItemConstructor creator = new ItemConstructor();
		    if (settings != null) {
				  if (settings.getBoolean("placeholder.enabled") == false) return;
				  creator.setMaterial(settings.getString("placeholder.material"));
				  creator.setName(settings.getString("placeholder.name"));
				  settings.getStringList("placeholder.lore").forEach(creator::addLore);
				  creator.setEnchanted(false);
				  for (int i = 45; i < 54; i++) {
						if (target.getItem(i) == null) {
							  target.setItem(i, creator.build());
						}
				  }
		    } else {
				  creator.setMaterial("GRAY_STAINED_GLASS_PANE");
				  creator.setName("&c");
				  creator.addLore("&7Placeholder");
				  creator.setEnchanted(false);
				  for (int i = 45; i < 54; i++) {
						if (target.getItem(i) == null) {
							  target.setItem(i, creator.build());
						}
				  }
		    }

	}

}
