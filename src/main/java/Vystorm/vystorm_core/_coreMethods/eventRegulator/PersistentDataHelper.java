package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;

/**
 * Typed item tags. Entity and block-state holders expose their containers directly.
 * <p>Preferred access: {@code api.nbt().getTag(...)} and {@code api.nbt().setTag(...)}.
 */
public final class PersistentDataHelper {
    private PersistentDataHelper() {}

    public static <P, C> C get(ItemStack item, NamespacedKey key, PersistentDataType<P, C> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, type);
    }

    /** A null value removes just this key. The supplied stack is updated and returned. */
    public static <P, C> ItemStack set(ItemStack item, NamespacedKey key,
                                     PersistentDataType<P, C> type, C value) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            DebugMessages.high("[PersistentDataHelper] Cannot update tags on an item without metadata support");
            throw new IllegalArgumentException("Item must support metadata");
        }
        if (value == null) meta.getPersistentDataContainer().remove(key);
        else meta.getPersistentDataContainer().set(key, type, value);
        item.setItemMeta(meta);
        DebugMessages.low("[PersistentDataHelper] Item tag " + (value == null ? "removed" : "updated") + "; key=" + key);
        return item;
    }
}
