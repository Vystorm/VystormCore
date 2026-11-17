package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.mythic.MmoItemsBridge;
import Vystorm.vystorm_core.mythic.MythicMobsBridge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ItemRefs}, reached through {@code api.items().refs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ItemRefs}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ItemRefsInterface {
    /** The delegating default. Plugins use {@code api.items().refs()}. */
    ItemRefsInterface DEFAULT = new ItemRefsInterface() {};

    /** See {@link ItemRefs#register}. */
    default void register(Plugin owner, String namespace, ItemRefResolver resolver) { ItemRefs.register(owner, namespace, resolver); }

    /** See {@link ItemRefs#register}. */
    default void register(Plugin owner, String namespace, int priority, ItemRefResolver resolver) {
        ItemRefs.register(owner, namespace, priority, resolver);
    }

    /** See {@link ItemRefs#unregisterAll}. */
    default void unregisterAll(Plugin owner) { ItemRefs.unregisterAll(owner); }

    /** See {@link ItemRefs#namespaces}. */
    default Set<String> namespaces() { return ItemRefs.namespaces(); }

    /** See {@link ItemRefs#available}. */
    default boolean available(String namespace) { return ItemRefs.available(namespace); }

    /** See {@link ItemRefs#providers}. */
    default List<String> providers(String namespace) { return ItemRefs.providers(namespace); }

    /** See {@link ItemRefs#resolve}. */
    default Optional<ItemStack> resolve(String ref, int amount) { return ItemRefs.resolve(ref, amount); }
}
