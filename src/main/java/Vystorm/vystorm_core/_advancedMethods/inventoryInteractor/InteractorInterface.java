package Vystorm.vystorm_core._advancedMethods.inventoryInteractor;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

public interface InteractorInterface {
void register(InteractorEntry interactor);

void unregister(InteractorEntry interactor);

InteractorEntry getByName(String name);

InteractorEntry getNBTForMatch(ItemStack item);

ItemStack setNBTforItem(ItemStack item, String name, String value);

// Is used for testing right now to understand how NBT under bukkit works and how to use it most efficiently
String getNBTforItem(ItemStack itemInUse);

ArrayList<String> getAllNamespacedKeys(ItemStack itemInUse);

String getValueForItem(ItemStack itemInHand, String key);


void handleEvent(InventoryClickEvent event);

void handleEvent(InventoryDragEvent event);

void handleEvent(PlayerDropItemEvent event);

boolean handle(InventoryClickEvent event);

boolean handle(InventoryDragEvent event);

boolean handle(PlayerDropItemEvent event);
    /** @deprecated Use {@code api.nbt().getTag(item, key, type)}. */
    @Deprecated(since = "0.64.0")
    default <P, C> C getTag(ItemStack item, org.bukkit.NamespacedKey key,
                            org.bukkit.persistence.PersistentDataType<P, C> type) {
        return Vystorm.vystorm_core.main.getApi().nbt().getTag(item, key, type);
    }

    /** @deprecated Use {@code api.nbt().setTag(item, key, type, value)}. */
    @Deprecated(since = "0.64.0")
    default <P, C> ItemStack setTag(ItemStack item, org.bukkit.NamespacedKey key,
                                    org.bukkit.persistence.PersistentDataType<P, C> type, C value) {
        return Vystorm.vystorm_core.main.getApi().nbt().setTag(item, key, type, value);
    }
}
