package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderHandler;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Starts the placeholders with Core: Core's own values, the bridge to PlaceholderAPI, and a listener that lets the
 * bridges look again whenever a plugin is enabled or disabled (PlaceholderAPI is enabled after Core, which loads at
 * startup).
 * <p>
 * Settings in Core's {@code config.yml}, section {@code placeholders}: {@code in-messages} (default false),
 * {@code placeholderapi.provide}, {@code placeholderapi.consume} (default true) and
 * {@code placeholderapi.consume-async} (default false). They are read at every use, so a change in the config editor
 * applies at once.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PlaceholderRuntime implements Listener {
    private final Plugin core;
    private final API api;

    private PlaceholderRuntime(Plugin core, API api) {
        this.core = core;
        this.api = api;
    }

    public static void start(Plugin core, API api) {
        PlaceholderHandler.shared().route(api::placeholders);
        configure(core);
        Lang lang = Localization.get().core();
        CorePlaceholders.register(core, api.placeholders(), () -> api, key -> lang.ref("placeholders.values." + key));
        api.placeholders().bridge(core, new PlaceholderApiBridge(
                () -> core.getServer().getPluginManager().getPlugin(PlaceholderApiBridge.PLUGIN),
                "Vystorm", core.getDescription().getVersion(),
                () -> core.getConfig().getBoolean("placeholders.placeholderapi.provide", true),
                () -> core.getConfig().getBoolean("placeholders.placeholderapi.consume", true),
                () -> core.getConfig().getBoolean("placeholders.placeholderapi.consume-async", false)));
        core.getServer().getPluginManager().registerEvents(new PlaceholderRuntime(core, api), core);
    }

    /** Reads the settings that are kept outside the config: whether language texts resolve placeholders. */
    public static void configure(Plugin core) {
        Localization.get().placeholdersInTexts(core.getConfig().getBoolean("placeholders.in-messages", false));
    }

    /** Removes every namespace of Core's registry and disconnects the bridges. Safe without {@link #start}. */
    public static void stop() {
        PlaceholderHandler.shared().clear();
        PlaceholderHandler.shared().route(null);
        Localization.get().placeholdersInTexts(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() != core) refresh();
    }

    /** The event comes before the plugin counts as disabled, so the bridges look one tick later. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == core || !core.isEnabled()) return;
        try { core.getServer().getScheduler().runTask(core, this::refresh); }
        catch (RuntimeException stopping) { /* the server is shutting down; Core's own disable disconnects */ }
    }

    private void refresh() {
        try { api.placeholders().refreshBridges(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Placeholders] Bridges not refreshed", failure); }
    }
}
