package Vystorm.vystorm_core.world;

import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Publishes central custom-world detection through the shared Core context bus.
 *
 * <p>Thread contract: callable from any thread. It only reads world metadata ({@code Bukkit.getWorld}, name,
 * environment, generator class) and never touches chunks or entities. Detectors registered in
 * {@link WorldEnvironmentRegistry} must follow the same rule.
 */
public final class WorldEnvironmentContextProvider implements RuntimeContextHub.Provider {
    @Override public String id() { return "core-world-environment"; }
    @Override public Set<RuntimeContextHub.Channel> channels() {
        return Set.of(RuntimeContextHub.Channel.WORLD_GENERATION);
    }
    @Override public int priority() { return 1000; }

    @Override
    public RuntimeContextHub.Snapshot snapshot(RuntimeContextHub.Channel channel, RuntimeContextHub.Query query) {
        World world = Bukkit.getWorld(query.worldId());
        if (world == null) return RuntimeContextHub.Snapshot.empty(id(), channel, priority(), 0L);
        var profile = WorldEnvironmentRegistry.get().detect(world);
        LinkedHashSet<String> tags = new LinkedHashSet<>(profile.tags());
        tags.add("DIMENSION:" + profile.dimension().name());
        profile.terrain().forEach(kind -> tags.add("TERRAIN:" + kind.name()));
        LinkedHashMap<String, String> values = new LinkedHashMap<>(profile.attributes());
        values.put("profile", profile.id());
        values.put("dimension", profile.dimension().name());
        values.put("generatorId", profile.generatorId());
        values.put("generatorClass", profile.generatorClass());
        values.put("customGeneration", Boolean.toString(profile.customGeneration()));
        return new RuntimeContextHub.Snapshot(id(), channel, priority(), profile.hashCode(), tags, values, Map.of());
    }
}
