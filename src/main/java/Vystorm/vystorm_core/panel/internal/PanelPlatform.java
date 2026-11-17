package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core.web.internal.ClientChannel;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Lebenszyklus von {@code vystorm:panel}: startet mit dem Client-Kanal (auch ohne Web-Plattform). Core-intern. @since 0.25.0 */
public final class PanelPlatform {
    private static volatile PanelChannel channel;
    private static volatile PanelHub hub;
    private static BukkitTask task;
    private static Listener quit;

    private PanelPlatform() {}

    public static boolean enabled() { return channel != null; }

    public static Optional<PanelChannel> channel() { return Optional.ofNullable(channel); }

    public static Optional<PanelHub> hub() { return Optional.ofNullable(hub); }

    public static synchronized void start(JavaPlugin core, PackageInterface packages, ClientChannel client) {
        stop(core, packages);
        PanelChannel.Settings settings = PanelChannel.Settings.from(core.getConfig().getConfigurationSection("web.client.panels"));
        if (!settings.enabled()) return;
        PanelChannel created = new PanelChannel(settings, client::capabilities,
                (player, data) -> packages.send(core, player, Protocol.CHANNEL_PANEL, data), core.getLogger(), System::nanoTime);
        PanelHub createdHub = new PanelHub(created, System::nanoTime, core.getLogger(), Bukkit.getPluginManager()::callEvent,
                work -> Bukkit.getScheduler().runTaskAsynchronously(core, work), work -> Bukkit.getScheduler().runTask(core, work));
        created.listener(createdHub);
        createdHub.dialogs(core, () -> Vystorm.vystorm_core.main.getApi().dialogs());
        packages.register(core, Protocol.CHANNEL_PANEL, (name, player, data) -> created.onMessage(player, data));
        task = Bukkit.getScheduler().runTaskTimer(core, () -> { createdHub.tick(); created.flush(); }, 1L, 1L);
        Listener l = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent event) {
                java.util.UUID uuid = event.getPlayer().getUniqueId();
                createdHub.quit(uuid);
                created.quit(uuid);
            }
        };
        Bukkit.getPluginManager().registerEvents(l, core);
        quit = l;
        hub = createdHub;
        channel = created;
        core.getLogger().info("[Panels] vystorm:panel ready (schema 1, hotkey panel " + created.hotkeyPanel() + ")");
    }

    public static synchronized void stop(JavaPlugin core, PackageInterface packages) {
        PanelHub h = hub;
        hub = null;
        if (h != null) h.clear();
        PanelChannel c = channel;
        channel = null;
        if (quit != null) { HandlerList.unregisterAll(quit); quit = null; }
        if (task != null) { try { task.cancel(); } catch (RuntimeException ignored) { } task = null; }
        if (c == null) return;
        c.clear();
        try { packages.unregister(core, Protocol.CHANNEL_PANEL); } catch (RuntimeException ignored) { }
    }
}
