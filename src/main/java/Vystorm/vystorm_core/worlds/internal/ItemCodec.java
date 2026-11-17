package Vystorm.vystorm_core.worlds.internal;

import java.util.Base64;
import org.bukkit.inventory.ItemStack;

/** Items ↔ Text für den Schnappschuss (im Test ersetzt). Leere Plätze bleiben erhalten. */
public interface ItemCodec {
    String encode(ItemStack[] items);

    ItemStack[] decode(String data);

    ItemCodec BUKKIT = new ItemCodec() {
        @Override public String encode(ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? Vystorm.vystorm_core.compat.PlatformCompat.emptyItem() : items[i];
            return Base64.getEncoder().encodeToString(Vystorm.vystorm_core.compat.ItemCompat.serialize(copy));
        }

        @Override public ItemStack[] decode(String data) {
            if (data == null || data.isEmpty()) return new ItemStack[0];
            return Vystorm.vystorm_core.compat.ItemCompat.deserialize(Base64.getDecoder().decode(data));
        }
    };
}
