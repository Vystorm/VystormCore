package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core.web.ClientUi;
import Vystorm.vystorm_core.web.VystormClientReadyEvent;
import Vystorm.vystorm_core.web.VystormClientStateEvent;
import Vystorm.vystorm_core.web.VystormClientUiRejectEvent;
import Vystorm.vystorm_core.web.WebLogin;
import Vystorm.vystorm_core.web.WebServices;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lebenszyklus des Client-Mod-Kanals ({@code vystorm:web}, seit 0.20.0 auch {@code vystorm:ui}): liest
 * {@code web.client}, meldet die Kanäle an (Bukkit kündigt sie dem Client per {@code minecraft:register} an), sendet
 * native Elemente jeden Tick gebündelt ({@link ClientUiHub#flush()}), ruft die Client-Events und räumt beim Quit auf
 * (inkl. Web-Sitzungen aus Client-Tokens, {@code web.client.end-sessions-on-quit}). Läuft auch ohne Web-Plattform
 * (WELCOME dann ohne Basis-URL, native Elemente gehen trotzdem). Nur Server-Thread. Core-intern.
 *
 * @since 0.19.0
 */
public final class ClientPlatform {
    private static volatile ClientChannel channel;
    private static volatile ClientUiHub hub;
    private static Listener listener;
    private static BukkitTask flushTask;
    private static final Map<String, Long> rejectLog = new HashMap<>();

    private ClientPlatform() {}

    public static Optional<ClientChannel> channel() { return Optional.ofNullable(channel); }

    /** Native Elemente ({@code vystorm:ui}), wenn der Kanal läuft und {@code web.client.ui.enabled}. @since 0.20.0 */
    public static Optional<ClientUiHub> uiHub() { return Optional.ofNullable(hub); }

    /** Startet den Kanal gemäß {@code web.client.enabled}. */
    public static synchronized void start(JavaPlugin core, PackageInterface packages) {
        stop(core, packages);
        ClientChannel.Settings settings = ClientChannel.Settings.from(core.getConfig().getConfigurationSection("web.client"));
        if (!settings.enabled()) return;
        Logger logger = core.getLogger();
        ClientUiHub ui = null;
        ClientUiHub.Settings uiSettings = ClientUiHub.Settings.from(core.getConfig().getConfigurationSection("web.client.ui"));
        if (settings.ui() && uiSettings.enabled()) {
            ui = new ClientUiHub(uiSettings, (player, data) -> packages.send(core, player, Protocol.CHANNEL_UI, data),
                    (player, id, code, detail) -> rejected(logger, player, id, code, detail), System::nanoTime);
        } else {
            settings = new ClientChannel.Settings(settings.enabled(), settings.overlay(), settings.preload(), settings.hotkeyModule(),
                    settings.openPerMinute(), false, settings.quitSessions());
        }
        ClientUiHub createdUi = ui;
        ClientChannel.Hooks hooks = new ClientChannel.Hooks() {
            @Override public void ready(Player player, int capabilities, String modVersion, String loader) {
                if (createdUi != null) createdUi.connected(player, capabilities);
                Bukkit.getPluginManager().callEvent(new VystormClientReadyEvent(player, capabilities, modVersion, loader));
            }

            @Override public void state(Player player, int previous, int state) {
                Bukkit.getPluginManager().callEvent(new VystormClientStateEvent(player, previous, state));
            }

            @Override public boolean panels(Player player, int capabilities) {
                return Vystorm.vystorm_core.panel.internal.PanelPlatform.enabled();
            }
        };
        ClientChannel created = new ClientChannel(settings, new PlatformLogins(),
                (player, name, data) -> packages.send(core, player, name, data), logger, System::nanoTime, hooks);
        packages.register(core, Protocol.CHANNEL_WEB, (name, player, data) -> created.onMessage(player, data));
        if (createdUi != null) {
            packages.register(core, Protocol.CHANNEL_UI, (name, player, data) -> createdUi.onMessage(player, data));
            flushTask = Bukkit.getScheduler().runTaskTimer(core, createdUi::flush, 1L, 1L);
        }
        ClientChannel.QuitSessions quitSessions = settings.quitSessions();
        Listener quit = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent event) {
                Player player = event.getPlayer();
                created.quit(player.getUniqueId());
                if (createdUi != null) createdUi.disconnected(player.getUniqueId());
                endSessions(player, quitSessions);
            }
        };
        Bukkit.getPluginManager().registerEvents(quit, core);
        listener = quit;
        hub = createdUi;
        channel = created;
        Vystorm.vystorm_core.panel.internal.PanelPlatform.start(core, packages, created);
    }

    private static void endSessions(Player player, ClientChannel.QuitSessions mode) {
        if (mode == ClientChannel.QuitSessions.NONE) return;
        try {
            if (mode == ClientChannel.QuitSessions.ALL) WebPlatform.revokeOnQuit(player.getUniqueId(), player.getName(), issuer -> true, "quit");
            else WebPlatform.revokeOnQuit(player.getUniqueId(), player.getName(), "client"::equals, "client quit");
        } catch (RuntimeException failure) {
            // Web-Plattform aus oder im Umbau: nichts zu beenden.
        }
    }

    /** REJECT vom Client: Event an das Plugin des Namensraums, Log höchstens einmal pro Minute je Namensraum+Code. */
    private static void rejected(Logger logger, Player player, String id, int code, String detail) {
        Plugin owner = ClientUi.ownerOf(id).orElse(null);
        try {
            Bukkit.getPluginManager().callEvent(new VystormClientUiRejectEvent(player, owner, id, code, detail));
        } catch (RuntimeException failure) {
            logger.log(java.util.logging.Level.WARNING, "[Web] Client reject listener failed", failure);
        }
        String key = (owner == null ? "?" : owner.getName()) + "#" + code;
        long now = System.nanoTime();
        synchronized (rejectLog) {
            Long last = rejectLog.get(key);
            if (last != null && now - last < 60_000_000_000L) return;
            if (rejectLog.size() > 256) rejectLog.clear();
            rejectLog.put(key, now);
        }
        logger.info("[Web] Client of " + player.getName() + " rejected UI element " + (id.isEmpty() ? "(none)" : id) + " (code " + code + "): "
                + Vystorm.vystorm_core.web.internal.clientproto.ui.UiText.sanitize(detail) + (owner == null ? "" : " [plugin " + owner.getName() + "]"));
    }

    /** Beendet den Kanal. Idempotent. */
    public static synchronized void stop(JavaPlugin core, PackageInterface packages) {
        Vystorm.vystorm_core.panel.internal.PanelPlatform.stop(core, packages);
        ClientChannel c = channel;
        ClientUiHub h = hub;
        channel = null;
        hub = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (flushTask != null) {
            try { flushTask.cancel(); } catch (RuntimeException ignored) { }
            flushTask = null;
        }
        if (h != null) {
            h.clear();
            try { packages.unregister(core, Protocol.CHANNEL_UI); } catch (RuntimeException ignored) { }
        }
        if (c == null) return;
        c.clear();
        try { packages.unregister(core, Protocol.CHANNEL_WEB); } catch (RuntimeException ignored) { }
    }

    /** Anbindung an {@link WebServices} (Rechte, Audit, Token). */
    private static final class PlatformLogins implements ClientChannel.Logins {
        @Override public String publicUrl() { return WebServices.available() ? WebServices.publicUrl().orElse("") : ""; }

        @Override public boolean registered(String module) { return WebServices.registered(module); }

        @Override public Optional<WebLogin> issue(Player player, String module) { return WebServices.issueLogin(player, module, "client"); }

        @Override public String title(String module) {
            return WebPlatform.modules().get(module).map(r -> r.module().title()).orElse("");
        }
    }
}
