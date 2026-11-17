package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Manager view of {@link ItemServices}, reached through {@code api.items()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ItemServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ItemServicesInterface {
    /** The delegating default. Plugins use {@code api.items()}. */
    ItemServicesInterface DEFAULT = new ItemServicesInterface() {};

    /** See {@link ItemServices#register}. */
    default void register(Plugin owner, ItemProvider provider) { ItemServices.register(owner, provider); }

    /** See {@link ItemServices#unregister}. */
    default void unregister(Plugin owner) { ItemServices.unregister(owner); }

    /** See {@link ItemServices#available}. */
    default boolean available() { return ItemServices.available(); }

    /** See {@link ItemServices#provider}. */
    default Optional<ItemProvider> provider() { return ItemServices.provider(); }

    /** See {@link ItemServices#describe}. */
    default Optional<ItemView> describe(ItemStack stack) { return ItemServices.describe(stack); }

    /** See {@link ItemServices#managed}. */
    default boolean managed(ItemStack stack) { return ItemServices.managed(stack); }

    /** See {@link ItemServices#create}. */
    default Optional<ItemStack> create(ItemCreateSpec spec) { return ItemServices.create(spec); }

    /** See {@link ItemServices#validate}. */
    default ItemValidation validate(ItemStack stack) { return ItemServices.validate(stack); }

    /** See {@link ItemServices#durability}. */
    default Optional<int[]> durability(ItemStack stack) { return ItemServices.durability(stack); }

    /** See {@link ItemServices#generateLoot}. */
    default List<ItemStack> generateLoot(String profileId, LootContext context) { return ItemServices.generateLoot(profileId, context); }

    /** See {@link ItemServices#equipmentStats}. */
    default Map<String, Double> equipmentStats(Player player) { return ItemServices.equipmentStats(player); }

    /** Immutable source breakdown supplied by the item provider. @since 0.35.0 */
    default Map<String, Map<String, Vystorm.vystorm_core.stat.StatSum.Contribution>> equipmentStatContributions(Player player) {
        return ItemServices.equipmentStatContributions(player);
    }

    /** See {@link ItemServices#repair}. */
    default Optional<ItemStack> repair(ItemStack stack, int amount, Player player) { return ItemServices.repair(stack, amount, player); }

    /** Prepare a managed anvil result without changing originals or committing item identity. @since 0.39.0 */
    default Optional<ItemStack> prepareAnvil(ItemStack original, ItemStack ingredient, ItemStack proposed, String rename, Player player) {
        return ItemServices.prepareAnvil(original, ingredient, proposed, rename, player);
    }

    /** Commit after actual delivery, never during PrepareAnvilEvent. @since 0.39.0 */
    default boolean commitAnvil(ItemStack delivered, Player player) { return ItemServices.commitAnvil(delivered, player); }

    /** See {@link ItemServices#damage}. */
    default Optional<ItemStack> damage(ItemStack stack, int amount, Player player) { return ItemServices.damage(stack, amount, player); }

    /** See {@link ItemServices#encode}. */
    default Optional<StoredItem> encode(ItemStack stack) { return ItemServices.encode(stack); }

    /** See {@link ItemServices#decode}. */
    default Optional<ItemStack> decode(StoredItem stored) { return ItemServices.decode(stored); }

    /** @since 0.11.0 */
    default boolean templateExists(String templateId) { return ItemServices.templateExists(templateId); }

    /** @since 0.11.0 */
    default Optional<String> provenance(UUID instanceId) { return ItemServices.provenance(instanceId); }

    /** See {@link ItemServices#fire}. */
    default void fire(Event event) { ItemServices.fire(event); }
}
