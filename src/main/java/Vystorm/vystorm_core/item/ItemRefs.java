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
 * Neutrale Item-Referenzen {@code namespace:id} für Mob-Equipment, Drops und Belohnungen. {@code minecraft:} (bzw. ohne
 * Namespace ein Material-Name) ist eingebaut; Vystorm-Items registriert {@code vystorm}, Migrationsadapter z. B. {@code mmoitems}.
 *
 * <p><b>Mehrere Provider pro Namespace (seit 0.12.0).</b> Ein Namespace darf von mehreren Plugins bedient werden, z. B.
 * {@code mythic} von Vystorm-Items (importierte Mythic-Items) und von einer MythicMobs-Bridge. {@link #resolve} fragt
 * die aktiven Provider in fester Reihenfolge; der erste, der die ID kennt, gewinnt:
 * <ol>
 *   <li>höhere {@code priority} zuerst ({@link #PRIORITY_VYSTORM} &gt; {@link #PRIORITY_DEFAULT} &gt; {@link #PRIORITY_BRIDGE}),</li>
 *   <li>bei gleicher Priorität das Plugin, das den {@link ItemServices}-Provider stellt (die Vystorm-Item-Engine),</li>
 *   <li>dann die jüngere Registrierung (wie das frühere last-writer-wins).</li>
 * </ol>
 * Danach folgen die eingebauten Rückfälle {@code mythic:<Item>} (MythicMobs-Items) und {@code mmoitems:TYPE:ID}
 * (MMOItems), jeweils nur, wenn das Plugin aktiv ist. Ein Provider, der eine ID nicht kennt, liefert {@code Optional.empty()};
 * wirft er, wird das gedrosselt geloggt und der nächste Provider gefragt. Registriert dasselbe Plugin einen Namespace
 * erneut, ersetzt es nur seinen eigenen Eintrag.
 * <p>Preferred access: {@code api.items().refs()}.
 */
public final class ItemRefs {
    /** Native Vystorm-Provider (Vystorm-Items, Vystorm-Mobs). @since 0.12.0 */
    public static final int PRIORITY_VYSTORM = 100;
    /** Priorität von {@link #register(Plugin, String, ItemRefResolver)}. @since 0.12.0 */
    public static final int PRIORITY_DEFAULT = 0;
    /** Bridges zu externen Plugins (MythicMobs, MMOItems); nur Rückfall. @since 0.12.0 */
    public static final int PRIORITY_BRIDGE = -100;

    private record Registered(Plugin owner, ItemRefResolver resolver, int priority, long sequence) {}

    private static final Map<String, List<Registered>> RESOLVERS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ItemRefs() {}

    public static void register(Plugin owner, String namespace, ItemRefResolver resolver) {
        register(owner, namespace, PRIORITY_DEFAULT, resolver);
    }

    /**
     * Registriert einen Resolver mit Priorität (siehe Klassendoku). Vystorm-Plugins nutzen {@link #PRIORITY_VYSTORM},
     * Bridges zu MythicMobs/MMOItems {@link #PRIORITY_BRIDGE}.
     *
     * @since 0.12.0
     */
    public static void register(Plugin owner, String namespace, int priority, ItemRefResolver resolver) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resolver, "resolver");
        String key = Objects.requireNonNull(namespace, "namespace").trim().toLowerCase(Locale.ROOT);
        Registered entry = new Registered(owner, resolver, priority, SEQUENCE.incrementAndGet());
        RESOLVERS.compute(key, (ignored, current) -> {
            List<Registered> next = new ArrayList<>();
            if (current != null) for (Registered existing : current) if (existing.owner() != owner) next.add(existing);
            next.add(entry);
            return List.copyOf(next);
        });
    }

    public static void unregisterAll(Plugin owner) {
        for (String key : Set.copyOf(RESOLVERS.keySet())) {
            RESOLVERS.computeIfPresent(key, (ignored, current) -> {
                List<Registered> next = current.stream().filter(entry -> entry.owner() != owner).toList();
                return next.isEmpty() ? null : next;
            });
        }
    }

    public static Set<String> namespaces() { return Set.copyOf(RESOLVERS.keySet()); }

    /** Ob ein aktiver Provider (oder der eingebaute MythicMobs-/MMOItems-Rückfall) den Namespace bedient. */
    public static boolean available(String namespace) {
        if (namespace == null) return false;
        String key = namespace.trim().toLowerCase(Locale.ROOT);
        return key.equals("minecraft") || !ordered(key).isEmpty() || builtinAvailable(key);
    }

    /**
     * Namen der aktiven Provider-Plugins eines Namespaces in Auflösungsreihenfolge (ohne eingebaute Rückfälle).
     *
     * @since 0.12.0
     */
    public static List<String> providers(String namespace) {
        if (namespace == null) return List.of();
        return ordered(namespace.trim().toLowerCase(Locale.ROOT)).stream().map(entry -> entry.owner().getName()).toList();
    }

    public static Optional<ItemStack> resolve(String ref, int amount) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        String text = ref.trim();
        int colon = text.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : text.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = colon < 0 ? text : text.substring(colon + 1);
        int count = Math.max(1, amount);
        if (namespace.equals("minecraft")) {
            Material material = Material.matchMaterial(id);
            return material == null || !material.isItem() || material.isAir() ? Optional.empty() : Optional.of(new ItemStack(material, count));
        }
        for (Registered entry : ordered(namespace)) {
            try {
                Optional<ItemStack> result = entry.resolver().resolve(id, count);
                if (result != null && result.isPresent()) return result.map(ItemStack::clone);
            } catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("ItemRefs", entry.owner().getName(), "resolve " + namespace, failure);
            }
        }
        return builtin(namespace, id, count);
    }

    private static List<Registered> ordered(String namespace) {
        List<Registered> entries = RESOLVERS.get(namespace);
        if (entries == null || entries.isEmpty()) return List.of();
        Plugin engine = itemEngine();
        List<Registered> active = new ArrayList<>(entries.size());
        for (Registered entry : entries) if (enabled(entry.owner())) active.add(entry);
        active.sort(Comparator.comparingInt((Registered entry) -> -entry.priority())
                .thenComparingInt(entry -> entry.owner() == engine ? 0 : 1)
                .thenComparingLong(entry -> -entry.sequence()));
        return active;
    }

    private static Plugin itemEngine() {
        try {
            return ItemServices.owner();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean enabled(Plugin owner) {
        try {
            return owner.isEnabled();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean builtinAvailable(String namespace) {
        try {
            return switch (namespace) {
                case "mythic" -> MythicMobsBridge.active();
                case "mmoitems" -> MmoItemsBridge.available();
                default -> false;
            };
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static Optional<ItemStack> builtin(String namespace, String id, int count) {
        try {
            return switch (namespace) {
                case "mythic" -> MythicMobsBridge.item(id, count);
                case "mmoitems" -> MmoItemsBridge.item(id, count);
                default -> Optional.empty();
            };
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report("ItemRefs", namespace.equals("mythic") ? "MythicMobs" : "MMOItems", "resolve " + namespace, failure);
            return Optional.empty();
        }
    }
}
