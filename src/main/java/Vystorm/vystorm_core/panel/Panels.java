package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core.panel.internal.PanelHub;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelPlatform;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Native Panels des Vystorm-Client-Mods (Spec §5): ein Panel ist eine Funktion des Zustands, Core prüft jede Aktion,
 * diffed Neu-Renderings und fällt ohne Mod auf das Plugin-Menü, einen automatischen Paper-Dialog oder einen Web-Link
 * zurück. Panel-IDs {@code <plugin>:<name>}. {@code register}, {@code unregister} und {@code open} nur auf dem
 * Server-Thread (sonst {@link IllegalStateException}); {@code refresh}, {@code refreshAll} und {@code close} dürfen von
 * jedem Thread kommen (Core springt auf den Server-Thread), {@code available} ebenso. Capability {@code panels}.
 *
 * <pre>{@code
 * Panels panels = Panels.of(this);
 * panels.register("mine", PanelSpec.builder("Meine Portale").render(this::minePanel).fallback(this::openChestMenu).build());
 * panels.open(player, "mine");
 * }</pre>
 *
 * <p>Preferred access: {@code api.gui().panels()}.
 *
 * @since 0.25.0
 */
public final class Panels {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]{1,31}");
    private static final Map<Plugin, Panels> INSTANCES = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final String namespace;

    private Panels(Plugin plugin) {
        this.plugin = plugin;
        String ns = plugin.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        this.namespace = ns.length() > 32 ? ns.substring(0, 32) : ns;
    }

    public static Panels of(Plugin plugin) { return INSTANCES.computeIfAbsent(Objects.requireNonNull(plugin), Panels::new); }

    /** Channel {@code vystorm:panel} läuft ({@code web.client.panels.enabled}). */
    public static boolean enabled() { return PanelPlatform.enabled(); }

    public String namespace() { return namespace; }

    public String id(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid panel name: " + name);
        return namespace + ":" + name;
    }

    public void register(String name, PanelSpec spec) {
        thread();
        String id = id(name);
        PanelRegistry.register(plugin, id, Objects.requireNonNull(spec));
        if (spec.menuCategory() != null) {
            Vystorm.vystorm_core.main.getApi().gui().registerMenu(plugin, name, spec.title(), spec.menuIcon(), spec.access(),
                    p -> open(p, name), spec.menuCategory().group(), spec.menuDescription());
        }
    }

    /** Entfernt das Panel und seinen {@code /vmenu}-Eintrag aus {@link PanelSpec.Builder#menu}. */
    public void unregister(String name) {
        thread();
        String id = id(name);
        boolean menu = PanelRegistry.find(id).filter(r -> r.owner() == plugin && r.spec().menuCategory() != null).isPresent();
        PanelRegistry.unregister(plugin, id);
        if (menu) Vystorm.vystorm_core.main.getApi().gui().unregisterMenu(plugin, name);
    }

    public PanelOpenResult open(Player player, String name) { return open(player, name, ""); }

    /** @param arg z. B. eine ID; kommt beim Rendern als {@code ctx.arg()} an */
    public PanelOpenResult open(Player player, String name, String arg) {
        thread();
        return PanelRegistry.find(id(name)).map(reg -> PanelOpener.open(player, reg, arg)).orElse(PanelOpenResult.NONE);
    }

    /** Neu rendern (Diff → PATCH, gebündelt pro Tick). Jeder Thread. */
    public void refresh(Player player, String name) {
        String id = id(name);
        onServerThread(() -> hub().ifPresent(h -> h.refresh(player, id)));
    }

    /** Für alle Spieler, die das Panel offen haben. Jeder Thread. */
    public void refreshAll(String name) {
        String id = id(name);
        onServerThread(() -> hub().ifPresent(h -> h.refreshAll(id)));
    }

    /** Schließt das Panel beim Spieler. Jeder Thread. */
    public void close(Player player, String name) {
        String id = id(name);
        onServerThread(() -> hub().ifPresent(h -> h.close(player, id)));
    }

    /** Spieler hat den Client-Mod mit Panels (Handshake fertig). Jeder Thread. */
    public boolean available(Player player) { return hub().map(h -> h.available(player)).orElse(false); }

    /** Core-Lebenszyklus: Plugin wird deaktiviert. */
    public static void release(Plugin owner) {
        PanelPlatform.hub().ifPresentOrElse(h -> h.release(owner), () -> PanelRegistry.release(owner));
        INSTANCES.remove(owner);
    }

    private static java.util.Optional<PanelHub> hub() { return PanelPlatform.hub(); }

    private void onServerThread(Runnable work) {
        if (Bukkit.isPrimaryThread()) work.run();
        else Bukkit.getScheduler().runTask(plugin, work);
    }

    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Panel operations require the server thread");
    }
}
