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
 * Item-Bridge (Items §29/§40): genau ein Provider (Vystorm-Items). Ohne Provider verhalten sich alle Aufrufe wie bei
 * Vanilla-Items (nicht verwaltet, keine Loot-Profile). Deep Inventory, Markt, Skills, Arena, Mobs und Director nutzen
 * nur diese Fassade, nie Klassen von Vystorm-Items.
 * <p>Preferred access: {@code api.items()}.
 */
public final class ItemServices {
    private record Registration(Plugin owner, ItemProvider provider) {}

    private static final AtomicReference<Registration> PROVIDER = new AtomicReference<>();
    private static final AtomicBoolean LIFECYCLE = new AtomicBoolean();

    private ItemServices() {}

    public static void register(Plugin owner, ItemProvider provider) {
        if (owner == null || provider == null) throw new IllegalArgumentException("owner/provider missing");
        Registration previous = PROVIDER.getAndSet(new Registration(owner, provider));
        if (previous != null) ProviderFailures.replaced("ItemServices", "Item-Provider", previous.owner(), owner);
        ensureLifecycle();
    }

    public static void unregister(Plugin owner) {
        PROVIDER.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current);
    }

    public static boolean available() { return PROVIDER.get() != null; }

    /** Owner des aktuellen Providers (für die Reihenfolge in {@link ItemRefs}). */
    static Plugin owner() { Registration r = PROVIDER.get(); return r == null ? null : r.owner(); }

    /**
     * Direkter Zugriff auf den Provider. Aufrufe darüber sind nicht abgesichert (Exceptions schlagen durch);
     * bevorzugt die Methoden dieser Fassade nutzen.
     */
    public static Optional<ItemProvider> provider() { Registration r = PROVIDER.get(); return r == null ? Optional.empty() : Optional.of(r.provider()); }

    /** Ruft den Provider abgesichert auf; Fehler werden gedrosselt geloggt und liefern {@code fallback}. */
    private static <T> T call(String operation, java.util.function.Function<ItemProvider, T> action, T fallback) {
        Registration registration = PROVIDER.get();
        if (registration == null) return fallback;
        try {
            T result = action.apply(registration.provider());
            return result == null ? fallback : result;
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report("ItemServices", registration.owner().getName(), operation, failure);
            return fallback;
        }
    }

    public static Optional<ItemView> describe(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        return call("describe", p -> p.describe(stack), Optional.empty());
    }

    public static boolean managed(ItemStack stack) { return describe(stack).isPresent(); }

    public static Optional<ItemStack> create(ItemCreateSpec spec) { return call("create", p -> p.create(spec), Optional.empty()); }

    /** Prüft das Item; Core-Sperren ({@link ItemRestrictions}) gehen jedem Provider vor (seit 0.26.0). */
    public static ItemValidation validate(ItemStack stack) {
        var restriction = ItemRestrictions.reason(stack);
        if (restriction.isPresent()) return ItemValidation.restricted(restriction.get());
        return call("validate", p -> p.validate(stack), ItemValidation.notManaged());
    }

    public static Optional<int[]> durability(ItemStack stack) { return call("durability", p -> p.durability(stack), Optional.empty()); }

    public static List<ItemStack> generateLoot(String profileId, LootContext context) {
        return call("generateLoot", p -> p.generateLoot(profileId, context), List.of());
    }

    public static Map<String, Double> equipmentStats(Player player) { return call("equipmentStats", p -> p.equipmentStats(player), Map.of()); }

    /** Immutable source breakdown supplied by the item provider. @since 0.35.0 */
    public static Map<String, Map<String, Vystorm.vystorm_core.stat.StatSum.Contribution>> equipmentStatContributions(Player player) {
        return call("equipmentStatContributions", provider -> {
            var result = new java.util.LinkedHashMap<String, Map<String, Vystorm.vystorm_core.stat.StatSum.Contribution>>();
            provider.equipmentStatContributions(player).forEach((stat, sources) -> result.put(stat, Map.copyOf(sources)));
            return Map.copyOf(result);
        }, Map.of());
    }

    /** Repariert über den Provider; leer ohne Provider oder bei Fehler. @since 0.11.0 */
    public static Optional<ItemStack> repair(ItemStack stack, int amount, Player player) {
        return call("repair", p -> Optional.ofNullable(p.repair(stack, amount, player)), Optional.empty());
    }

    /** Prepare a managed anvil result without changing originals or committing item identity. @since 0.39.0 */
    public static Optional<ItemStack> prepareAnvil(ItemStack original, ItemStack ingredient, ItemStack proposed, String rename, Player player) {
        Registration registration = PROVIDER.get();
        if (registration == null || !registration.owner().isEnabled()) return Optional.empty();
        if (original == null || proposed == null || player == null || !validate(original).ok()) return Optional.empty();
        return call("prepareAnvil", p -> p.prepareAnvil(original.clone(), ingredient == null ? null : ingredient.clone(),
                proposed.clone(), rename, player).map(ItemStack::clone), Optional.empty());
    }

    /** Commit after actual delivery, never during PrepareAnvilEvent. @since 0.39.0 */
    public static boolean commitAnvil(ItemStack delivered, Player player) {
        Registration registration = PROVIDER.get();
        if (registration == null || !registration.owner().isEnabled()) return false;
        if (delivered == null || player == null) return false;
        return call("commitAnvil", p -> p.commitAnvil(delivered.clone(), player), false);
    }

    /** Nutzt Haltbarkeit über den Provider ab (Luft = zerbrochen); leer ohne Provider oder bei Fehler. @since 0.11.0 */
    public static Optional<ItemStack> damage(ItemStack stack, int amount, Player player) {
        return call("damage", p -> Optional.ofNullable(p.damage(stack, amount, player)), Optional.empty());
    }

    /** Verlustfreie Lager-Repräsentation; leer ohne Provider, für nicht verwaltete Items oder bei Fehler. @since 0.11.0 */
    public static Optional<StoredItem> encode(ItemStack stack) { return call("encode", p -> p.encode(stack), Optional.empty()); }

    /** Stellt ein gelagertes Item wieder her; leer ohne Provider oder bei Fehler (dann das gelagerte Item nicht löschen). @since 0.11.0 */
    public static Optional<ItemStack> decode(StoredItem stored) {
        if (stored == null) return Optional.empty();
        return call("decode", p -> Optional.ofNullable(p.decode(stored)), Optional.empty());
    }

    /** @since 0.11.0 */
    public static boolean templateExists(String templateId) { return templateId != null && call("templateExists", p -> p.templateExists(templateId), false); }

    /** @since 0.11.0 */
    public static Optional<String> provenance(UUID instanceId) {
        if (instanceId == null) return Optional.empty();
        return call("provenance", p -> p.provenance(instanceId), Optional.empty());
    }

    /**
     * Event feuern (vom Provider aufzurufen). Off-thread wird es auf den nächsten Tick verschoben; cancellable Events
     * deshalb nur vom Server-Thread feuern, sonst gehen Cancel und Änderungen verloren (wird geloggt).
     */
    public static void fire(Event event) {
        if (Bukkit.isPrimaryThread() || event.isAsynchronous()) { Bukkit.getPluginManager().callEvent(event);return; }
        if (event instanceof org.bukkit.event.Cancellable)
            ProviderFailures.report("ItemServices", event.getEventName(), "fire off-thread",
                    new IllegalStateException("Cancellable event fired off the server thread; cancel/changes are not visible to the caller"));
        Plugin core = main.getPlugin();
        if (core != null && core.isEnabled()) Bukkit.getScheduler().runTask(core, () -> Bukkit.getPluginManager().callEvent(event));
    }

    private static void ensureLifecycle() {
        if (LIFECYCLE.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !LIFECYCLE.compareAndSet(false, true)) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDisable(PluginDisableEvent event) { unregister(event.getPlugin());ItemRefs.unregisterAll(event.getPlugin()); }
        }, core);
    }
}
