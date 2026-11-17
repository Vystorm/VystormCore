package Vystorm.vystorm_core._uiMethods.commands.methods;

import org.bukkit.inventory.Inventory;

import java.util.HashMap;

/** Unused legacy API, kept for binary compatibility; does nothing. */
@Deprecated(since = "0.63.0", forRemoval = true)
public class CommandGuiSetup {
    public HashMap<String, Inventory> createInventory(HashMap eins, HashMap zwei, HashMap drei, HashMap vier) {
        return new HashMap<String, Inventory>();
    }
}
