package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._coreMethods.inventoryEventHandler.InventoryEventHandler;
import Vystorm.vystorm_core._coreMethods.inventoryEventHandler.InventoryEventInterface;

import java.util.Objects;

public class InventoryEventSingelton {
    private InventoryEventInterface instance;

    public InventoryEventInterface getInstance() {
        if (instance == null) instance = new InventoryEventHandler();
        return instance;
    }

    public void override(InventoryEventInterface replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (instance != null && instance != replacement) instance.clear();
        instance = replacement;
    }

    public void removeAll(org.bukkit.plugin.Plugin owner) { if (instance != null) instance.removeAll(owner); }

    public void clear() { if (instance != null) instance.clear(); }
}
