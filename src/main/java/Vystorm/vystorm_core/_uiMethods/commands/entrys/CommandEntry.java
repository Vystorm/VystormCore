package Vystorm.vystorm_core._uiMethods.commands.entrys;

import org.bukkit.inventory.ItemStack;

public interface CommandEntry {
	    //logic of the command to be run from the gui
	    void run(String[] args);
	    String requiredArgument();

	    //should be hardcoded in the command entry class or taken from a config file
	    String name();
	    String description();
	    String permission();
	    ItemStack item();

}
