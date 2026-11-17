package Vystorm.vystorm_core._coreMethods.inventoryEventHandler;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A synchronous use observation. It does not prove an item was consumed. */
public final class InventoryUse {
    private final Player player;
    private final int slot;
    private final ItemStack item;
    private final BooleanSupplier valid;

    InventoryUse(Player player, int slot, ItemStack item, BooleanSupplier valid) {
        this.player = Objects.requireNonNull(player, "player");
        if (slot < 0 || (slot > 8 && slot != 40)) throw new IllegalArgumentException("slot must be 0..8 or 40");
        this.slot = slot;
        this.item = Objects.requireNonNull(item, "item").clone();
        this.valid = Objects.requireNonNull(valid, "valid");
    }

    public Player player() { return player; }
    public int slot() { return slot; }
    public ItemStack item() { return item.clone(); }
    /** Evaluates the final event, owner, lifecycle and player guards when the consumer needs it. */
    public boolean valid() {
        try { return valid.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }
}
