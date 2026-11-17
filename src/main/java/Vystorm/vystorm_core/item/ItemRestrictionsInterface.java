package Vystorm.vystorm_core.item;

import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Manager view of {@link ItemRestrictions}, reached through {@code api.items().restrictions()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ItemRestrictions}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ItemRestrictionsInterface {
    /** The delegating default. Plugins use {@code api.items().restrictions()}. */
    ItemRestrictionsInterface DEFAULT = new ItemRestrictionsInterface() {};

    /** See {@link ItemRestrictions#mark}. */
    default ItemStack mark(ItemStack stack, String reason) { return ItemRestrictions.mark(stack, reason); }

    /** See {@link ItemRestrictions#restricted}. */
    default boolean restricted(ItemStack stack) { return ItemRestrictions.restricted(stack); }

    /** See {@link ItemRestrictions#reason}. */
    default Optional<String> reason(ItemStack stack) { return ItemRestrictions.reason(stack); }
}
