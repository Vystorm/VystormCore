package Vystorm.vystorm_core.mythic;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link MmoItemsBridge}, reached through {@code api.items().mmoItems()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link MmoItemsBridge}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface MmoItemsBridgeInterface {
    /** The delegating default. Plugins use {@code api.items().mmoItems()}. */
    MmoItemsBridgeInterface DEFAULT = new MmoItemsBridgeInterface() {};

    /** See {@link MmoItemsBridge#available}. */
    default boolean available() { return MmoItemsBridge.available(); }

    /** See {@link MmoItemsBridge#item}. */
    default Optional<ItemStack> item(String typeAndId, int amount) { return MmoItemsBridge.item(typeAndId, amount); }

    /** See {@link MmoItemsBridge#idOf}. */
    default Optional<String> idOf(ItemStack stack) { return MmoItemsBridge.idOf(stack); }
}
