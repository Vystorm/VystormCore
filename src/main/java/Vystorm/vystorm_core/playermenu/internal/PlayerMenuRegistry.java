package Vystorm.vystorm_core.playermenu.internal;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Entries of the player menu by owner. Registration is thread-safe; {@link #render}, {@link #visible} and {@link #click}
 * call plugin code and belong on the server thread. Every plugin call is contained: a failure hides (or cancels) only
 * that entry and is reported through {@link ProviderFailures}. Not public API.
 */
public final class PlayerMenuRegistry {
    static final String REGISTRY = "PlayerMenu";
    private static final PlayerMenuRegistry INSTANCE = new PlayerMenuRegistry();

    /** A registration: {@code key} is {@code <plugin lowercase>:<id>}, {@code order} the registration sequence. */
    public record Registered(Plugin owner, String key, PlayerMenuEntry entry, long order) {}
    /** A visible entry with the icon drawn for one viewer/target pair. */
    public record Rendered(Registered registered, ItemStack icon) {
        public String key() { return registered.key(); }
    }

    private static final Comparator<Registered> ORDER =
            Comparator.comparingInt((Registered r) -> r.entry().priority()).thenComparingLong(Registered::order);

    private final Map<String, Registered> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public PlayerMenuRegistry() {}

    public static PlayerMenuRegistry get() { return INSTANCE; }

    public String register(Plugin owner, PlayerMenuEntry entry) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(entry, "entry");
        if (!owner.isEnabled()) throw new IllegalStateException("player menu owner " + owner.getName() + " is not enabled");
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + entry.id();
        entries.put(key, new Registered(owner, key, entry, sequence.incrementAndGet()));
        return key;
    }

    public boolean unregister(Plugin owner, String id) {
        if (owner == null || id == null) return false;
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + id;
        Registered current = entries.get(key);
        return current != null && current.owner() == owner && entries.remove(key, current);
    }

    /** Removes every entry of {@code owner} (plugin disable). */
    public void release(Plugin owner) {
        if (owner != null) entries.values().removeIf(r -> r.owner() == owner);
    }

    public List<String> keys() { return sorted().stream().map(Registered::key).toList(); }

    public Optional<Registered> find(String key) { return Optional.ofNullable(key == null ? null : entries.get(key)); }

    /** Visible entries with their icons, sorted, at most {@link PlayerMenu#MAX_ENTRIES}. */
    public List<Rendered> render(Player viewer, Player target) {
        List<Rendered> out = new ArrayList<>();
        for (Registered registered : sorted()) {
            if (out.size() >= PlayerMenu.MAX_ENTRIES) break;
            if (!visible(registered, viewer, target)) continue;
            ItemStack icon = icon(registered, viewer, target);
            if (icon != null) out.add(new Rendered(registered, icon));
        }
        return List.copyOf(out);
    }

    /** Whether the entry is still registered, its owner enabled and its predicate true; failures count as hidden. */
    public boolean visible(Registered registered, Player viewer, Player target) {
        if (registered == null || entries.get(registered.key()) != registered || !enabled(registered.owner())) return false;
        try {
            return registered.entry().visible().test(viewer, target);
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report(REGISTRY, registered.key(), "visibleWhen", failure);
            return false;
        }
    }

    /**
     * Runs the click handler. {@code null} result means KEEP.
     *
     * @return the result, or empty if the handler threw
     */
    public Optional<PlayerMenu.Result> click(Registered registered, Player viewer, Player target, ClickType click) {
        try {
            PlayerMenu.Result result = registered.entry().click().onClick(viewer, target, click);
            return Optional.of(result == null ? PlayerMenu.Result.KEEP : result);
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report(REGISTRY, registered.key(), "onClick", failure);
            return Optional.empty();
        }
    }

    void clear() { entries.clear(); }

    private ItemStack icon(Registered registered, Player viewer, Player target) {
        try {
            ItemStack icon = registered.entry().icon().apply(viewer, target);
            return icon == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(icon) ? null : icon;
        } catch (RuntimeException | LinkageError failure) {
            ProviderFailures.report(REGISTRY, registered.key(), "icon", failure);
            return null;
        }
    }

    private List<Registered> sorted() {
        List<Registered> all = new ArrayList<>(entries.values());
        all.sort(ORDER);
        return all;
    }

    private static boolean enabled(Plugin owner) {
        try { return owner.isEnabled(); } catch (RuntimeException failure) { return false; }
    }
}
