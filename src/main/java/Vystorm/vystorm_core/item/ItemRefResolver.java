package Vystorm.vystorm_core.item;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Löst Item-Referenzen eines Namespaces auf (z. B. {@code vystorm:ORC_WAR_AXE}). Server-Thread. */
@FunctionalInterface
public interface ItemRefResolver {
    Optional<ItemStack> resolve(String id, int amount);
}
