package Vystorm.vystorm_core.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Manager view of {@link StorageCompat}, reached through {@code api.compat().storage()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link StorageCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface StorageCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().storage()}. */
    StorageCompatInterface DEFAULT = new StorageCompatInterface() {};

    /** See {@link StorageCompat#serializeAsBytes}. */
    default byte[] serializeAsBytes(ItemStack item) { return StorageCompat.serializeAsBytes(item); }

    /** See {@link StorageCompat#deserializeBytes}. */
    default ItemStack deserializeBytes(byte[] data) { return StorageCompat.deserializeBytes(data); }

    /** Serializes a single animal/entity, excluding passengers just like Paper's default. */
    default byte[] serializeEntity(Entity entity) { return StorageCompat.serializeEntity(entity); }

    /** Returns a detached entity with a fresh UUID. Its original PDC identity is preserved. */
    default Entity deserializeEntity(byte[] data, World world) { return StorageCompat.deserializeEntity(data, world); }

    /** Adds a detached entity once, respecting normal Bukkit spawn cancellation. */
    default boolean spawnAt(Entity entity, Location location) { return StorageCompat.spawnAt(entity, location); }
}
