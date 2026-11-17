package Vystorm.vystorm_core.mythic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.mythic.MythicMobsBridge.*;

/**
 * Manager view of {@link MythicMobsBridge}, reached through {@code api.mobs().mythic()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MythicMobsBridge}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MythicMobsBridgeInterface {
    /** The delegating default. Plugins use {@code api.mobs().mythic()}. */
    MythicMobsBridgeInterface DEFAULT = new MythicMobsBridgeInterface() {};

    /** See {@link MythicMobsBridge#available}. */
    default boolean available() { return MythicMobsBridge.available(); }

    /** See {@link MythicMobsBridge#active}. */
    default boolean active() { return MythicMobsBridge.active(); }

    /** See {@link MythicMobsBridge#item}. */
    default Optional<ItemStack> item(String id, int amount) { return MythicMobsBridge.item(id, amount); }

    /** See {@link MythicMobsBridge#exists}. */
    default boolean exists(String mobId) { return MythicMobsBridge.exists(mobId); }

    /** See {@link MythicMobsBridge#spawn}. */
    default Entity spawn(String mobId, Location location, int level) { return MythicMobsBridge.spawn(mobId, location, level); }

    /** See {@link MythicMobsBridge#installPack}. */
    default boolean installPack(Plugin owner, String resource, String fileName) throws IOException {
        return MythicMobsBridge.installPack(owner, resource, fileName);
    }
}
