package Vystorm.vystorm_core.mythic;

import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Optional MythicMobs custom item drops, resolved lazily through ItemRefs.
 * Mythic owns the chance/amount roll; this bridge never dispatches commands or
 * spawns extra entities. A unique item remains a single instance, following the
 * ItemRefs contract; use separate entries when dropping multiple unique items.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class MythicItemDrops implements Listener, AutoCloseable {
    private final Plugin owner;
    private final Listener drops = new Listener() {};
    private ClassLoader bound;
    private boolean closed;

    private MythicItemDrops(Plugin owner) { this.owner = owner; }

    public static MythicItemDrops start(Plugin owner) {
        MythicItemDrops bridge = new MythicItemDrops(owner);
        Bukkit.getPluginManager().registerEvents(bridge, owner);
        bridge.bind();
        return bridge;
    }

    @EventHandler
    public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("MythicMobs")) bind();
    }

    @SuppressWarnings("unchecked")
    private void bind() {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (closed || mythic == null) return;
        ClassLoader loader = mythic.getClass().getClassLoader();
        if (bound == loader) return;
        try {
            Class<? extends Event> event = (Class<? extends Event>) Class.forName(
                    "io.lumine.mythic.bukkit.events.MythicDropLoadEvent", false, loader).asSubclass(Event.class);
            Class<?> config = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", false, loader);
            Class<?> drop = Class.forName("io.lumine.mythic.api.drops.IItemDrop", false, loader);
            Class<?> idrop = Class.forName("io.lumine.mythic.api.drops.IDrop", false, loader);
            Method adapt = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", false, loader)
                    .getMethod("adapt", ItemStack.class);
            Method getString = config.getMethod("getString", String.class, String.class);
            HandlerList.unregisterAll(drops);
            Bukkit.getPluginManager().registerEvent(event, drops, EventPriority.NORMAL, (listener, raw) -> {
                try {
                    String name = (String) event.getMethod("getDropName").invoke(raw);
                    Object line = event.getMethod("getConfig").invoke(raw);
                    String id = (String) getString.invoke(line, "id", "");
                    String type = (String) getString.invoke(line, "type", "");
                    Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
                    Optional<String> reference = reference(name, type, id, mmo != null && mmo.isEnabled());
                    if (reference.isEmpty()) return;
                    Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{drop}, (self, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                            case "toString" -> "CoreItemDrop[" + reference.get() + "]";
                            case "hashCode" -> System.identityHashCode(self);
                            case "equals" -> self == args[0];
                            default -> null;
                        };
                        if (!method.getName().equals("getDrop")) return null;
                        ItemStack result = new ItemStack(Material.AIR);
                        try {
                            if (closed || !owner.isEnabled() || !Bukkit.isPrimaryThread())
                                throw new IllegalStateException("Item drop outside an active server-thread context");
                            int amount = amount(((Number) args[1]).doubleValue());
                            if (amount > 0) result = ItemRefs.resolve(reference.get(), amount)
                                    .orElseThrow(() -> new IllegalStateException("Unresolved item " + reference.get()));
                        } catch (RuntimeException | LinkageError failure) {
                            ProviderFailures.report("MythicItemDrops", owner.getName(), reference.get(), failure);
                        }
                        return adapt.invoke(null, result);
                    });
                    event.getMethod("register", idrop).invoke(raw, proxy);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    ProviderFailures.report("MythicItemDrops", owner.getName(), "load drop", failure);
                }
            }, owner);
            bound = loader;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ProviderFailures.report("MythicItemDrops", owner.getName(), "register", failure);
        }
    }

    static Optional<String> reference(String name, String type, String id, boolean mmoActive) {
        if (name == null || id == null || id.isBlank()) return Optional.empty();
        String drop = name.toLowerCase(Locale.ROOT);
        if (drop.equals("mmoitems") && !mmoActive && type != null && type.matches("[A-Za-z0-9_]+")
                && id.matches("[A-Za-z0-9_.-]+"))
            return Optional.of("mmoitems:" + type + ":" + id);
        if ((drop.equals("vystorm") || drop.equals("vystormitem")) && id.matches("[A-Za-z0-9_./:-]+"))
            return Optional.of(id.contains(":") ? id : "vystorm:" + id);
        return Optional.empty();
    }

    static int amount(double value) {
        return Double.isFinite(value) && value > 0 ? (int) Math.min(64, value) : 0;
    }

    @Override public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(drops);
        bound = null;
    }
}
