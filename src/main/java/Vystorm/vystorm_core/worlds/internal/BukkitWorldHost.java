package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;
import Vystorm.vystorm_core.world.WorldEnvironmentRegistry;
import Vystorm.vystorm_core.worlds.RegionSpec;
import Vystorm.vystorm_core.worlds.SpawnPoint;
import Vystorm.vystorm_core.worlds.WorldSpec;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Server-Umsetzung von {@link ManagedWorldRegistry.Host}. */
final class BukkitWorldHost implements ManagedWorldRegistry.Host {
    private static final Method LEVEL_DIRECTORY = levelDirectoryMethod();

    private static Method levelDirectoryMethod() {
        try { return Server.class.getMethod("getLevelDirectory"); }
        catch (NoSuchMethodException legacyServer) { return null; }
    }

    private final WorldsWarnings warnings = new WorldsWarnings(this::log);
    @Override public boolean stopping() { return Vystorm.vystorm_core.compat.PlatformCompat.isStopping(); }
    @Override public boolean primaryThread() { return Bukkit.isPrimaryThread(); }
    @Override public World loaded(String name) { World world = Bukkit.getWorld(name);return world != null ? world : Vystorm.vystorm_core.compat.WorldCompat.getWorld(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))); }

    @Override public Path defaultFolder(String worldName) {
        // 26.x uses keyed dimension folders; 1.21.x uses named folders in the world container.
        // An error reading a modern level directory must never fall back to a different folder.
        if (LEVEL_DIRECTORY == null) return resolveFolder(null, Bukkit.getWorldContainer().toPath(), worldName);
        try {
            Path level = java.util.Objects.requireNonNull((Path) LEVEL_DIRECTORY.invoke(Bukkit.getServer()));
            return resolveFolder(level, null, worldName);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Cannot resolve the server level directory", failure);
        }
    }

    static Path resolveFolder(Path level, Path container, String name) {
        return (level == null ? container.resolve(name)
                : level.resolve("dimensions/minecraft").resolve(name.toLowerCase(Locale.ROOT)))
                .toAbsolutePath().normalize();
    }

    @Override public World create(WorldSpec spec) {
        WorldCreator creator = LEVEL_DIRECTORY == null ? new WorldCreator(spec.worldName())
                : Vystorm.vystorm_core.compat.WorldCompat.worldCreator(NamespacedKey.minecraft(spec.worldName().toLowerCase(Locale.ROOT)));
        return creator.environment(spec.environment()).generateStructures(spec.generateStructures())
                .generator(spec.generator()).createWorld();
    }

    @Override public boolean unload(World world, boolean save) { return Bukkit.unloadWorld(world, save); }
    @Override public Path folderOf(World world) { return world.getWorldFolder().toPath(); }

    @Override public void configure(World world, WorldSpec spec) {
        for (Map.Entry<GameRule<?>, Object> rule : spec.gameRules().entrySet()) setRule(world, rule.getKey(), rule.getValue());
        if (spec.time() != null) world.setTime(spec.time());
        if (spec.lockWeather()) { world.setStorm(false);world.setThundering(false);world.setClearWeatherDuration(Integer.MAX_VALUE / 2); }
        if (spec.viewDistance() > 0) Vystorm.vystorm_core.compat.WorldCompat.setViewDistance(world,spec.viewDistance());
        if (spec.simulationDistance() > 0) Vystorm.vystorm_core.compat.WorldCompat.setSimulationDistance(world,spec.simulationDistance());
        world.setAutoSave(spec.autoSave());
        if (spec.difficulty() != null) world.setDifficulty(spec.difficulty());
        if (spec.borderSize() > 0) { world.getWorldBorder().setCenter(0, 0);world.getWorldBorder().setSize(spec.borderSize()); }
        SpawnPoint spawn = spec.spawn();
        if (spawn != null) world.setSpawnLocation(new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
    }

    @SuppressWarnings("unchecked")
    private static <T> void setRule(World world, GameRule<T> rule, Object value) { world.setGameRule(rule, (T) value); }

    @Override public void attach(Plugin owner, World world, WorldSpec spec) {
        RegionSpec r = spec.region();
        if (r != null) {
            try {
                Region region = Region.of(r.kind(), r.id(), new RegionShape.Box(world.getUID(), r.minX(), world.getMinHeight(), r.minZ(), r.maxX(), world.getMaxHeight(), r.maxZ()))
                        .withPriority(r.priority()).withOwner(owner.getName()).withTags(r.tags()).withFlags(r.flags());
                RegionService.get().register(owner, region);
            } catch (RuntimeException | LinkageError failure) { log("Region of " + spec.id() + " not registered: " + failure); }
        }
        if (spec.profile() != null) {
            try { WorldEnvironmentRegistry.get().setExplicitProfile(owner, world, spec.profile()); }
            catch (RuntimeException | LinkageError failure) { log("World profile of " + spec.id() + " not set: " + failure); }
        }
        if (spec.generatorId() != null) {
            try { Vystorm.vystorm_core.main.getApi().worldGenerator().register(spec.generatorId(), spec.generator()); }
            catch (RuntimeException | LinkageError failure) { log("Generator of " + spec.id() + " not registered: " + failure); }
        }
    }

    @Override public void detach(World world, WorldSpec spec) {
        RegionSpec r = spec.region();
        if (r != null) try { RegionService.get().unregister(r.kind(), r.id()); } catch (RuntimeException | LinkageError failure) { warnings.warn("detach", "Abmelden von " + spec.id() + " fehlgeschlagen: " + failure); }
        try { WorldEnvironmentRegistry.get().clearExplicitProfile(world); } catch (RuntimeException | LinkageError failure) { warnings.warn("detach", "Abmelden von " + spec.id() + " fehlgeschlagen: " + failure); }
        if (spec.generatorId() != null) try { Vystorm.vystorm_core.main.getApi().worldGenerator().unregister(spec.generatorId()); } catch (RuntimeException | LinkageError failure) { warnings.warn("detach", "Abmelden von " + spec.id() + " fehlgeschlagen: " + failure); }
    }

    @Override public void evacuate(World world) {
        Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        for (Player player : List.copyOf(world.getPlayers())) player.teleport(spawn);
    }

    @Override public void log(String message) { DebugMessages.high("[ManagedWorlds] " + message); }
}
