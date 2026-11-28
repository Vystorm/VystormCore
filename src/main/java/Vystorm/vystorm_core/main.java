package Vystorm.vystorm_core;

import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseHandler;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.notifications.RoutineMessages;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core._uiMethods.settings.FirstRunSetup;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.SettingPolicy;
import Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboarding;
import Vystorm.vystorm_core.bundleclasses.CommandsSummary;
import Vystorm.vystorm_core.bundleclasses.Listener;
import Vystorm.vystorm_core.bundleclasses.StartupGuis;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.compat.ClickActions;
import Vystorm.vystorm_core.compat.ClientEvents;
import Vystorm.vystorm_core.compat.GameplayCompat;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.database.SharedDatabase;
import Vystorm.vystorm_core.database.SharedDatabaseSettings;
import Vystorm.vystorm_core.inbox.internal.InboxService;
import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.licensing.LicensedModules;
import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import Vystorm.vystorm_core.lifecycle.ReviewReminder;
import Vystorm.vystorm_core.lifecycle.UpdateChecker;
import Vystorm.vystorm_core.lifecycle.UpdateSources;
import Vystorm.vystorm_core.loot.LootTableConfig;
import Vystorm.vystorm_core.loot.MobLoot;
import Vystorm.vystorm_core.metrics.CoreMetrics;
import Vystorm.vystorm_core.mythic.MythicItemDrops;
import Vystorm.vystorm_core.network.paper.NetworkCommands;
import Vystorm.vystorm_core.network.paper.PaperNetwork;
import Vystorm.vystorm_core.news.internal.NewsService;
import Vystorm.vystorm_core.pack.AssetRequirement;
import Vystorm.vystorm_core.pack.ResourcePacks;
import Vystorm.vystorm_core.packet.ClientBiomes;
import Vystorm.vystorm_core.packet.ClientChunks;
import Vystorm.vystorm_core.player.PlayerActivityActionHandler;
import Vystorm.vystorm_core.player.PlayerActivityService;
import Vystorm.vystorm_core.playermenu.internal.MenuShortcut;
import Vystorm.vystorm_core.playermenu.internal.PlayerMenuTrigger;
import Vystorm.vystorm_core.poll.internal.PollService;
import Vystorm.vystorm_core.profile.internal.ProfileService;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.runtime.RuntimeActionHub;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import Vystorm.vystorm_core.screen.internal.HudRuntime;
import Vystorm.vystorm_core.screen.internal.InventoryScreens;
import Vystorm.vystorm_core.screen.internal.ScreenDemo;
import Vystorm.vystorm_core.screen.internal.ScreenEntities;
import Vystorm.vystorm_core.screen.internal.ScreenListener;
import Vystorm.vystorm_core.screen.internal.ScreenMapFrames;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import Vystorm.vystorm_core.screen.internal.ScreenShader;
import Vystorm.vystorm_core.threat.ThreatServices;
import Vystorm.vystorm_core.trace.TraceLifecycle;
import Vystorm.vystorm_core.visual.Visuals;
import Vystorm.vystorm_core.web.internal.ClientPlatform;
import Vystorm.vystorm_core.web.internal.WebPlatform;
import Vystorm.vystorm_core.world.WorldEnvironmentContextProvider;
import Vystorm.vystorm_core.worldevent.WorldEvents;
import Vystorm.vystorm_core.worlds.internal.WorldsPlatform;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class main extends JavaPlugin {
private static API api;
Listener listener = new Listener();
CommandsSummary commandsSummary = new CommandsSummary();
StartupGuis startupGuis = new StartupGuis();
private PlayerActivityService playerActivity;
  private PollService polls;
private MythicItemDrops mythicItemDrops;

@Override
public void onEnable() {
        PlatformCompat.initialize(this);
        GameplayCompat.initialize(this);
        ClientEvents.initialize(this);
        try { FirstRunSetup.snapshot(this); }
        catch (RuntimeException | LinkageError failure) { getLogger().warning("First-run setup state not read: " + failure); }
        this.api = new API("default");
        DebugMessages.mid("[main] Enabling Vystorm Core");
        saveDefaultConfig();
        try { LootTableConfig.reloadCore(this); }
        catch (Exception failure) { getLogger().severe("Shared loot tables not loaded: " + failure.getMessage()); }
        Vystorm.vystorm_core.lifecycle.CoreSwitches.configure(getConfig());
        MobLoot.configure(lootSettings());
        Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages.shared().configure(getConfig().getConfigurationSection("event-order"));
        try { CoreLanguage.enable(this); }
        catch (RuntimeException | LinkageError failure) { getLogger().warning("Localization not started: " + failure); }
        api.gui().createGui();
        commandsSummary.register(this);
        start("Command system", () -> {
                // Localized root aliases follow on the first tick (server fully started, every plugin's own labels exist).
                CoreCommands.start(this);
                polls = new PollService(this);
        });
        listener.register(this);
        startupGuis.register(api);
        registerOwnConfig();
        start("Config menu", () -> ConfigEditor.startMenu(this));
        CoreOnboarding.register(this, api);
        RoutineMessages.start(this, api);
        start("Player menu", () -> PlayerMenuTrigger.start(this, api));
        start("News board", () -> NewsService.start(this, api));
        start("Inbox", () -> InboxService.start(this, api));
        start("Profile cards", () -> ProfileService.start(this, api));
        // Known players (0.71.0): names for searches without Paper-only calls; the file is read off the server thread
        start("Known players", () -> Vystorm.vystorm_core.knownplayers.KnownPlayers.start(this));
        // Player picker (0.71.0): Core panel vystorm_core:player-pick with its chest fallback
        start("Player picker", () -> Vystorm.vystorm_core.picker.Pickers.start(this));
        start("Placeholders", () -> Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal.PlaceholderRuntime.start(this, api));
        // Screen menus from plugins/vystorm_core/menus (0.65.0); a broken file is skipped, never a reason to stop.
        start("File menus", () -> Vystorm.vystorm_core.menus.internal.FileMenuService.start(this, api));
        // Nach /reload oder Plugman: Daten bereits eingeloggter Spieler laden.
        for (Player player : getServer().getOnlinePlayers()) {
            try { api.player().loadPlayer(player.getUniqueId()); }
            catch (RuntimeException failure) { DebugMessages.high("[main] Player data load failed for " + player.getName(), failure); }
        }

        playerActivity = new PlayerActivityService(this);
        getServer().getPluginManager().registerEvents(playerActivity, this);
        RuntimeContextHub.get().register(this, playerActivity);
        RuntimeContextHub.get().register(this, new WorldEnvironmentContextProvider());
        RuntimeActionHub.get().register(this, new PlayerActivityActionHandler(playerActivity));
        ResourcePacks.initialize();
        // Vystorm Screens (0.57.0): a failure here must never take the rest of Core down.
        try { ScreenShader.register(this); startScreens(); }
        catch (RuntimeException | LinkageError screensFailed) { getLogger().log(Level.SEVERE, "Vystorm Screens could not start; menus fall back to the classic ones", screensFailed); }
        WorldEvents.get().initialize(this);
        getServer().getServicesManager().register(API.class, api, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new CoreLifecycle(), this);
        getServer().getPluginManager().registerEvents(Visuals.lifecycleListener(), this);
        ClientBiomes.initialize(this);
        ClientChunks.initialize(this);
        // Client blocks (0.72.0): per-player block sets; Visuals block views run on them
        start("Client blocks", () -> Vystorm.vystorm_core.packet.ClientBlocks.initialize(this));
        // Packet view (0.73.0): only a listener here; self-test and handlers once a plugin registers a layer
        start("Packet view", () -> Vystorm.vystorm_core.packet.PacketView.initialize(this));
        mythicItemDrops = MythicItemDrops.start(this);
        RegionService.get();
        start("Managed worlds", () -> WorldsPlatform.start(this));
        // World survey (0.68.0): nothing runs unless survey.enabled is on or a plugin requires it; read on the first
        // tick, when the worlds exist (Core loads at STARTUP).
        getServer().getScheduler().runTask(this, () -> start("World survey", () -> Vystorm.vystorm_core.survey.WorldSurvey.configure(this, getConfig())));
        getServer().getScheduler().runTaskTimerAsynchronously(this, ThreatServices::purgeExpired, 1200L, 1200L);
        startSharedDatabase();
        start("Network", () -> {
            PaperNetwork.start(this);
            NetworkCommands.register(this);
        });
        start("Trace tools", () -> TraceLifecycle.start(this));
        startWeb();
        UpdateSources.spigot(this, 139848);
        start("Update check", () -> UpdateChecker.start(this));
        start("Review reminder", () -> ReviewReminder.start(this));
        start("Metrics", () -> CoreMetrics.start(this));

        DebugMessages.mid("[main] Vystorm Core enabled; runtime hubs, world detection, activity state and startup services registered");
}

/** Runs one start step; a failure is logged as "[main] <name> not started" and Core keeps starting. */
private static void start(String name, Runnable step) {
        try { step.run(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] " + name + " not started", failure); }
}

/** True when the player's client has Core's pack applied (also an older version of it while a new one is on its way). */
static boolean packApplied(org.bukkit.entity.Player player) {
    Vystorm.vystorm_core.pack.PackState state = ResourcePacks.state(player);
    return state == Vystorm.vystorm_core.pack.PackState.LOADED || state == Vystorm.vystorm_core.pack.PackState.OUTDATED;
}

/** Protocol of Minecraft 26.3, the first client that draws maps before see-through text. */
static final int PROTOCOL_26_3 = 777;

/** The player's client protocol (Paper's {@code Player#getProtocolVersion}); -1 where the server cannot tell (Spigot). */
static int clientProtocol(org.bukkit.entity.Player player) {
    try { return (int) player.getClass().getMethod("getProtocolVersion").invoke(player); }
    catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) { return -1; }
}

/** Map widgets of PINNED screens show on this client (0.65.1: not on 26.3 and newer, see ScreenRuntime#mapsDrawableFor). */
static boolean mapWidgetsDrawable(org.bukkit.entity.Player player) {
    int protocol = clientProtocol(player);
    if (protocol >= 0) return protocol < PROTOCOL_26_3;
    try { return !Vystorm.vystorm_core.screen.internal.ScreenRuntime.atLeast263(org.bukkit.Bukkit.getBukkitVersion()); }
    catch (RuntimeException | LinkageError unknown) { return true; }
}

/** Vystorm Screens runtime (0.57.0): sessions, listeners, leftover cleanup. */
private void startScreens() {
    ScreenRuntime runtime = new ScreenRuntime(this,
            new ScreenEntities.Bukkit(this), System::currentTimeMillis,
            p -> ResourcePacks.hasRequiredAssets(p, AssetRequirement.of(ScreenShader.MODULE)),
            p -> PlayerPrefs.bool(this, p, "screen.classic", false),
            task -> getServer().getScheduler().runTaskLater(this, task, 2L));
    runtime.useMaps(new ScreenMapFrames.Packets());
    // 0.66.2: the wheel notches of one tick become one Scroll and one relayout
    runtime.coalesceWheel(task -> getServer().getScheduler().runTask(this, task));
    // 0.65.1: without Core's pack applied (declined, failed download or reload, not sent) screens fall back to the classic menu
    runtime.requirePack(main::packApplied);
    runtime.mapsDrawableFor(main::mapWidgetsDrawable);
    // 0.62.0: screens.enabled false = no screens at all, screens.off = single menus or plugins; both show the fallback
    runtime.blockWhen((owner, keys) -> !getConfig().getBoolean("screens.enabled", true)
            || ScreenRuntime.listed(getConfig().getStringList("screens.off"), owner, keys));
    // 0.71.1: a refused screen always says why in chat (at most every few seconds), whoever opened it
    runtime.noticeRefusals((p, key) -> Vystorm.vystorm_core.lang.Localization.get().core().send(p, key));
    // 0.71.1: players invisible without an effect (left by 0.71.0 and older) are reported; admins are told the fix
    runtime.invisibleRules(
            p -> p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY),
            () -> getConfig().getBoolean("screens.repair-invisible-on-join", false),
            p -> p.hasPermission("vystorm_core.admin"),
            (p, key) -> Vystorm.vystorm_core.lang.Localization.get().core().send(p, key, "player", p.getName()));
    // after Core clears its own flag, a running invisibility effect must count again: off and on with the same values
    runtime.invisibleEffectRefresh(p -> {
        org.bukkit.potion.PotionEffect running = p.getPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        if (running == null) return;
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        p.addPotionEffect(running);
    });
    ScreenRuntime.install(runtime);
    getServer().getPluginManager().registerEvents(new ScreenListener(), this);
    // a reload: players online now joined before this listener existed
    for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
        try { runtime.onJoin(online); } catch (RuntimeException | LinkageError failure) { getLogger().log(Level.WARNING, "Invisible check of " + online.getName() + " failed", failure); }
    }
    // 0.65.0: a click that closes or replaces a screen must not reach the block or mob behind it
    try { Vystorm.vystorm_core.screen.internal.ScreenWorldGuard.register(api.priority().stages(), this, ScreenRuntime::get); }
    catch (RuntimeException | LinkageError noStages) { getLogger().log(Level.WARNING, "Screen click guard not started", noStages); }
    try { Class.forName("org.bukkit.event.player.PlayerInputEvent"); getServer().getPluginManager().registerEvents((org.bukkit.event.Listener) Class.forName("Vystorm.vystorm_core.screen.internal.ScreenKeys").getDeclaredConstructor().newInstance(), this); }
    catch (ReflectiveOperationException | LinkageError noInput) { }
    for (World world : getServer().getWorlds()) runtime.sweep(world.getEntities());
    ScreenDemo.register(this);
    getServer().getPluginManager().registerEvents(new InventoryScreens(this), this);
    HudRuntime.install(this, main::packApplied);
    getServer().getPluginManager().registerEvents(new MenuShortcut(System::currentTimeMillis,
            () -> getConfig().getBoolean("menu.sneak-click-shortcut", false),
            p -> ScreenRuntime.get().session(p).isPresent() || Vystorm.vystorm_core.picker.internal.WorldPickRuntime.picking(p)), this);
}

@Override
@SuppressWarnings("deprecation")
public void onDisable() {
     try { Vystorm.vystorm_core.picker.Pickers.shutdown(); } catch (RuntimeException | LinkageError ignored) { }
     try { ScreenRuntime.uninstall(); } catch (RuntimeException | LinkageError ignored) { }
        try { TraceLifecycle.stop(); } catch (RuntimeException | LinkageError ignored) { }
        try { Vystorm.vystorm_core.survey.WorldSurvey.shutdown(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] World survey stop failed", failure); }
        try { PaperNetwork.stop(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Network stop failed", failure); }
        try { Vystorm.vystorm_core.blocks.BlockWrites.shutdown(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Chunk loads stop failed", failure); }
        if (polls != null) polls.close();
        UpdateChecker.stop();
        ReviewReminder.stop();
        NewsService.stop();
        InboxService.stop();
        try { Vystorm.vystorm_core.menus.internal.FileMenuService.stop(); } catch (RuntimeException | LinkageError ignored) { }
        try { Vystorm.vystorm_core.knownplayers.KnownPlayers.shutdown(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Known players not saved", failure); }
        CoreMetrics.stop();
        WorldEvents.get().close();
     ClientBiomes.shutdown();
     ClientChunks.shutdown();
     Visuals.shutdown();
     try { Vystorm.vystorm_core.packet.ClientBlocks.shutdown(); } catch (RuntimeException | LinkageError ignored) { }
     try { Vystorm.vystorm_core.packet.PacketView.shutdown(); } catch (RuntimeException | LinkageError ignored) { }
     LicensedModules.shutdown();
     if (mythicItemDrops != null) mythicItemDrops.close();
     CoreOnboarding.stop();
     try { RoutineMessages.stop(); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Stopping routine messages failed", failure); }
     // Zuerst: Besuche beenden (Zustand zurück, Spielerdaten speichern) und Plugin-Welten entladen.
     try { WorldsPlatform.stop(); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Stopping managed worlds failed", failure); }
     try { CoreLanguage.disable(); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Stopping localization failed", failure); }
     try { if (api != null) ClientPlatform.stop(this, api.packages()); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Stopping the client channel failed", failure); }
     try { WebPlatform.stop(); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Stopping the web platform failed", failure); }
     ResourcePacks.shutdown();
     try { SharedDatabase.shutdown(); }
     catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Closing the shared database failed", failure); }
     RuntimeActionHub.get().unregisterOwner(this);
     RuntimeContextHub.get().unregisterOwner(this);
     if (playerActivity != null) playerActivity.shutdown();
     ClientEvents.shutdown();
     ClickActions.shutdown();
     PlatformCompat.shutdown();
     if (api == null) return;
     try {
         DebugMessages.mid("[main] Disabling Vystorm Core; saving registered data");
         // Paper deaktiviert Plugins vor dem Kick beim Stop; Quit-Listener laufen dann nicht mehr.
         for (Player player : getServer().getOnlinePlayers()) {
             try { api.player().unloadPlayer(player.getUniqueId()); }
             catch (Exception failure) { DebugMessages.high("[main] Player data save failed for " + player.getName(), failure); }
         }
         try { api.shutdownAdvanced(); }
         finally {
             try { api.data().saver().saveAll(); }
             finally {
                 try { api.database().disconnectALL(); }
                 catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Closing database pools failed", failure); }
             }
         }
         DebugMessages.mid("[main] Registered data saved; Vystorm Core disabled");
     } finally {
         api.shutdownDebugger();
     }
}

/**
 * Core's own config.yml in the config editor ({@code /vadmin config vystorm_core}). {@code database.shared},
 * {@code web.gateway.secret}/{@code url} and other secrets are never shown. Language changes apply at once,
 * web settings after a restart.
 */
private void registerOwnConfig() {
        try {
                ConfigEditor.activation(this, "web",
                        SettingPolicy.Activation.RESTART);
                ConfigEditor.activation(this, "database",
                        SettingPolicy.Activation.RESTART);
                ConfigEditor.activation(this, "known-players",
                        SettingPolicy.Activation.RESTART);
                LinkedHashMap<String, String> modes = new LinkedHashMap<>();
                modes.put("embedded", "embedded"); modes.put("gateway", "gateway");
                ConfigEditor.choices(this, "web.mode", modes);
                LinkedHashMap<String, String> quit = new LinkedHashMap<>();
                quit.put("client", "client"); quit.put("all", "all"); quit.put("none", "none");
                ConfigEditor.choices(this, "web.client.end-sessions-on-quit", quit);
                ConfigEditor.register(this, "config.yml",
                        () -> { reloadConfig(); var switchesBefore = Vystorm.vystorm_core.lifecycle.CoreSwitches.off();
                                Vystorm.vystorm_core.lifecycle.CoreSwitches.configure(getConfig()); MobLoot.configure(lootSettings()); CoreLanguage.reload(); CoreCommands.configure(getConfig().getConfigurationSection("commands"));
                                // a switch changed: the players get their command list again (completion follows at once)
                                if (!switchesBefore.equals(Vystorm.vystorm_core.lifecycle.CoreSwitches.off())) CoreCommands.refresh();
                                applyStartSwitches();
                                Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal.PlaceholderRuntime.configure(this);
                                Vystorm.vystorm_core.menus.FileMenus.reload();
                                Vystorm.vystorm_core.survey.WorldSurvey.configure(this, getConfig());
                                Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages.shared().configure(getConfig().getConfigurationSection("event-order")); });
                for (String lootFile : LootTableConfig.CORE_FILES)
                        ConfigEditor.register(this, lootFile, () -> {
                                try { LootTableConfig.reloadCore(this); }
                                catch (Exception failure) { throw new IllegalStateException("Loot tables rejected; previous tables retained", failure); }
                        });
                declareSwitches();
                DebugMessages.mid("[main] Core config registered in the config editor");
        } catch (RuntimeException | LinkageError failure) {
                DebugMessages.high("[main] Core config editor registration failed", failure);
        }
}

/** Mob-level luck and pity from {@code loot}; with {@code loot.enabled: false} drops ignore the mob's level. */
private MobLoot.Settings lootSettings() {
        if (!Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.LOOT))
                return new MobLoot.Settings(0, 0, null, null, null);
        return MobLoot.Settings.of(getConfig());
}

/**
 * The switches that used to be read only at start apply at once (0.65.0): the update check and the review reminder
 * start or stop when their {@code enabled} changed, and the message source on hover follows {@code messages.show-source}.
 */
private void applyStartSwitches() {
        try { Vystorm.vystorm_core.trace.MessageSource.configure(getConfig().getBoolean("messages.show-source", true)); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[main] Message source not reconfigured", failure); }
        // diagnostics.enabled off: /vadmin trace is off as well, so no trace may keep running without a way to stop it
        if (!Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.DIAGNOSTICS))
                try { Vystorm.vystorm_core.trace.TraceLifecycle.stop(); } catch (RuntimeException | LinkageError ignored) { }
        boolean updates = getConfig().getBoolean("update-check.enabled", true);
        if (updates != (Vystorm.vystorm_core.lifecycle.UpdateChecker.get() != null)) {
                if (updates) start("Update check", () -> Vystorm.vystorm_core.lifecycle.UpdateChecker.start(this));
                else Vystorm.vystorm_core.lifecycle.UpdateChecker.stop();
        }
        boolean reviews = getConfig().getBoolean("review-reminder.enabled", true);
        if (reviews != Vystorm.vystorm_core.lifecycle.ReviewReminder.running()) {
                if (reviews) start("Review reminder", () -> Vystorm.vystorm_core.lifecycle.ReviewReminder.start(this));
                else Vystorm.vystorm_core.lifecycle.ReviewReminder.stop();
        }
}

/**
 * Core's switches in {@code /vadmin features} with a one-line description in the admin's language: the new ones of
 * 0.65.0 and the older keys that were switchable but not on the feature screen. {@code metrics} needs a restart.
 */
private void declareSwitches() {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        // 0.66.1: with readable names in all languages (before, the names came from the key paths)
        Vystorm.vystorm_core.lifecycle.CoreSwitches.declare(lang::ref, (path, label, description, declared) -> {
                if (declared) ConfigEditor.feature(this, path, label, description);
                else ConfigEditor.label(this, path, label, description);
        });
        ConfigEditor.activation(this, "metrics", SettingPolicy.Activation.RESTART);
}

/** Reads {@code database.shared} and connects asynchronously; a failure only logs, Core keeps running. */
private void startSharedDatabase() {
        try {
                saveDefaultConfig();
                SharedDatabaseSettings settings = SharedDatabaseSettings.from(getConfig().getConfigurationSection("database.shared"));
                SharedDatabase.start(settings, getLogger(), dataSource -> {
                        if (api.database() instanceof DataBaseHandler handler && dataSource instanceof HikariDataSource pool)
                                handler.registerDataSource(SharedDatabase.ID, pool, DataBaseInterface.DataBaseType.MYSQL);
                });
        } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Shared database not started: " + failure);
                DebugMessages.high("[main] Shared database start failed", failure);
        }
}

/** Reads {@code web} and starts the web platform if enabled; a failure only logs, Core keeps running. */
private void startWeb() {
        try {
                WebPlatform.startFromConfig(this);
        } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Web platform not started: " + failure);
                DebugMessages.high("[main] Web platform start failed", failure);
        }
        try {
                ClientPlatform.start(this, api.packages());
        } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Client mod channel not started: " + failure);
                DebugMessages.high("[main] Client channel start failed", failure);
        }
}

/**
 * @deprecated Kein Bukkit-Hook, wird nie aufgerufen. Bleibt nur aus Kompatibilitätsgründen öffentlich.
 */
@Deprecated
public void onReload() {
        DebugMessages.mid("[main] Reload requested; no reload implementation is configured");
}

/**
 * Legacy-Einstieg in die Core-API. Vor {@code onEnable} null, nach {@code onDisable} die veraltete Instanz;
 * neuer Code nutzt {@link CoreApi#api()} (leer, solange Core nicht aktiv ist) oder den Bukkit-ServicesManager
 * ({@code API.class}).
 */
public static API getApi(){ return api; }
public PlayerActivityService playerActivity() { return playerActivity; }
/** Das Core-Plugin (Bukkit liefert es über den Classloader). */
public static Plugin getPlugin() { return main.getProvidingPlugin(main.class); }
}
