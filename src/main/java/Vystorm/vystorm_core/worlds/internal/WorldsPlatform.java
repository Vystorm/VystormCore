package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.worlds.ManagedWorldException;
import java.nio.file.Path;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Verdrahtung der Plugin-Welten in Core: Register, Besuchsdienst, Listener (immer aktiv) und {@code /vworlds}.
 * {@link #start} in {@code main.onEnable}, {@link #stop} als Erstes in {@code main.onDisable}.
 */
public final class WorldsPlatform {
    private static volatile WorldsPlatform instance;

    private final ManagedWorldRegistry registry;
    private final VisitEngine visits;

    private WorldsPlatform(ManagedWorldRegistry registry, VisitEngine visits) {
        this.registry = registry;
        this.visits = visits;
    }

    public static WorldsPlatform get() {
        WorldsPlatform current = instance;
        if (current == null) throw new ManagedWorldException(ManagedWorldException.Reason.ILLEGAL_CALL, "Vystorm Core is not enabled");
        return current;
    }

    public ManagedWorldRegistry registry() { return registry; }

    public VisitEngine visits() { return visits; }

    public static synchronized void start(JavaPlugin core) {
        if (instance != null) return;
        Path data = core.getDataFolder().toPath();
        BukkitVisitEnv env = new BukkitVisitEnv(core);
        ManagedWorldRegistry registry = new ManagedWorldRegistry(new BukkitWorldHost(), data.resolve("worlds"));
        VisitEngine visits = new VisitEngine(env, registry, new VisitSnapshotStore(data.resolve("visits")));
        registry.endVisitsWith(visits::endAll);
        core.getServer().getPluginManager().registerEvents(new WorldsListener(visits, registry, env), core);
        var spawnListener = VisitSpawnListeners.create(visits);
        if (spawnListener != null) core.getServer().getPluginManager().registerEvents(spawnListener, core);
        PluginCommand command = core.getCommand("vworlds");
        if (command != null) {
            WorldsCommand worlds = new WorldsCommand(registry, visits, env);
            // plugin-worlds.enabled off (0.65.0): /vworlds is off; visits keep saving and restoring the players' state
            org.bukkit.command.TabExecutor executor = new org.bukkit.command.TabExecutor() {
                @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
                    if (!Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.PLUGIN_WORLDS)) {
                        Vystorm.vystorm_core.lang.Localization.get().core().send(sender, "feature-off");
                        return true;
                    }
                    return worlds.onCommand(sender, cmd, label, args);
                }
                @Override public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
                    return Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.PLUGIN_WORLDS) ? worlds.onTabComplete(sender, cmd, label, args) : java.util.List.of();
                }
            };
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        instance = new WorldsPlatform(registry, visits);
        DebugMessages.mid("[ManagedWorlds] started; " + new VisitSnapshotStore(data.resolve("visits")).all().size() + " open visit snapshots");
    }

    public static synchronized void stop() {
        WorldsPlatform current = instance;
        if (current == null) return;
        try { current.visits.shutdown(); } catch (RuntimeException | LinkageError failure) { DebugMessages.high("[ManagedWorlds] ending visits failed", failure); }
        try { current.registry.shutdown(); } catch (RuntimeException | LinkageError failure) { DebugMessages.high("[ManagedWorlds] unloading worlds failed", failure); }
        instance = null;
    }
}
