package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.storage.VersionedStore;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.item.RewardPouch.*;

/**
 * Manager view of {@link RewardPouch}, reached through {@code api.loot().pouches()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link RewardPouch}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface RewardPouchInterface {
    /** The delegating default. Plugins use {@code api.loot().pouches()}. */
    RewardPouchInterface DEFAULT = new RewardPouchInterface() {};

    /** See {@link RewardPouch#open}. */
    default RewardPouch open(Plugin owner, API core, String namespace) throws Exception { return RewardPouch.open(owner, core, namespace); }
}
