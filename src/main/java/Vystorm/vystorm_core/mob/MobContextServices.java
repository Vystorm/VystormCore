package Vystorm.vystorm_core.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Register der {@link ContextProvider}. Arena, Cities, Factions, World Events und Dungeons liefern hier Kontext;
 * sie greifen nie direkt in die Mob-AI ein.
 * <p>Preferred access: {@code api.mobs().context()}.
 */
public final class MobContextServices {
    private record Registered(Plugin owner, ContextProvider provider) {}

    private static final Map<String, Registered> PROVIDERS = new ConcurrentHashMap<>();

    private MobContextServices() {}

    public static void register(Plugin owner, ContextProvider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        Registered previous = PROVIDERS.putIfAbsent(provider.id(), new Registered(owner, provider));
        if (previous != null && previous.owner() != owner) throw new IllegalStateException("Context provider already registered: " + provider.id());
        PROVIDERS.put(provider.id(), new Registered(owner, provider));
    }

    public static void unregisterAll(Plugin owner) { PROVIDERS.values().removeIf(entry -> entry.owner() == owner); }

    public static List<String> providers() { return List.copyOf(PROVIDERS.keySet()); }

    /** Sammelt alle Ebenen; defekte Provider werden übersprungen. Server-Thread. */
    public static MobContext resolve(MobContextQuery query) {
        List<ContextLayer> layers = new ArrayList<>();
        for (Registered entry : PROVIDERS.values()) {
            if (!entry.owner().isEnabled()) continue;
            try {
                List<ContextLayer> result = entry.provider().layers(query);
                if (result != null) layers.addAll(result);
            } catch (RuntimeException failure) {
                // Provider-Ausfall darf Mobs nicht blockieren
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("MobContextServices", entry.provider().id(), "layers", failure);
            }
        }
        return layers.isEmpty() ? MobContext.EMPTY : new MobContext(layers, CombatRules.OPEN_WORLD);
    }
}
