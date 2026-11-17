package Vystorm.vystorm_core.compat;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Manager view of {@link ItemCompat}, reached through {@code api.compat().items()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ItemCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ItemCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().items()}. */
    ItemCompatInterface DEFAULT = new ItemCompatInterface() {};

    /** See {@link ItemCompat#model}. */
    default String model(ItemMeta meta) { return ItemCompat.model(meta); }

    /** See {@link ItemCompat#serialize}. */
    default byte[] serialize(ItemStack[] items) { return ItemCompat.serialize(items); }

    /** See {@link ItemCompat#deserialize}. */
    default ItemStack[] deserialize(byte[] data) { return ItemCompat.deserialize(data); }
}
