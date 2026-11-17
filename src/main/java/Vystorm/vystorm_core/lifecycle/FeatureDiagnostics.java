package Vystorm.vystorm_core.lifecycle;

import java.util.*;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Provider-owned, read-only feature health probes. Server thread only; no repair callbacks. @since 0.26.5
 * <p>Preferred access: {@code api.admin().diagnostics()}.
 */
public final class FeatureDiagnostics {
    public enum State { ACTIVE, FALLBACK, UNAVAILABLE }
    /** reason may be a Lang.ref; never include secrets or connection strings. */
    public record Status(String feature, String provider, State state, String reason) {}
    private record Probe(Plugin owner, String id, Function<Player, Status> check) {}
    private static final Map<String, Probe> PROBES = new LinkedHashMap<>();
    private FeatureDiagnostics() {}
    public static void register(Plugin owner, String id, Function<Player, Status> check) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(check);
        if (id == null || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid diagnostic id");
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + id;
        if (!PROBES.containsKey(key) && PROBES.values().stream().filter(p -> p.owner == owner).count() >= 64)
            throw new IllegalStateException("Too many diagnostic probes");
        PROBES.put(key, new Probe(owner, key, check));
    }
    public static List<Status> inspect(Player target) {
        thread(); List<Status> out = new ArrayList<>();
        for (Probe probe : List.copyOf(PROBES.values())) {
            if (!probe.owner.isEnabled()) continue;
            try { out.add(Objects.requireNonNull(probe.check.apply(target))); }
            catch (RuntimeException | LinkageError ex) {
                ProviderFailures.report("FeatureDiagnostics", probe.owner.getName(), probe.id, ex);
                out.add(new Status(probe.id, probe.owner.getName(), State.UNAVAILABLE, "{lang:vystorm_core:diagnostics.probe-failed}"));
            }
        }
        return List.copyOf(out);
    }
    public static void unregister(Plugin owner) { thread(); PROBES.values().removeIf(p -> p.owner == owner); }
    private static void thread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Diagnostics require the server thread"); }
}
