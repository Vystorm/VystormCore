package Vystorm.vystorm_core.storage;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Explicit plugin-owned converters run against detached bytes, never a live writable store.
 * Adapters must be pure and report validation problems instead of silently skipping records.
 * Server thread only. This registry cannot sandbox arbitrary plugin code. @since 0.26.5
 * <p>Preferred access: {@code api.data().migrationPreviews()}.
 */
public final class MigrationPreviews {
    public record Report(long beforeCount, long afterCount, Map<String, BigDecimal> beforeSums,
                         Map<String, BigDecimal> afterSums, List<String> problems) {
        public Report { beforeSums = Map.copyOf(beforeSums); afterSums = Map.copyOf(afterSums); problems = List.copyOf(problems); }
        public boolean conserved() { return problems.isEmpty() && beforeCount == afterCount && beforeSums.equals(afterSums); }
    }
    @FunctionalInterface public interface Converter { Report convert(byte[] detachedSource) throws Exception; }
    private record Entry(Plugin owner, Path source, Converter converter) {}
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private MigrationPreviews() {}
    public static void register(Plugin owner, String id, Path source, Converter converter) {
        thread(); Objects.requireNonNull(owner); Objects.requireNonNull(source); Objects.requireNonNull(converter);
        if (id == null || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid migration id");
        Path root = owner.getDataFolder().toPath().toAbsolutePath().normalize(), path = source.toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Migration source must belong to the plugin data folder");
        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + id;
        if (!ENTRIES.containsKey(key) && ENTRIES.values().stream().filter(e -> e.owner == owner).count() >= 32)
            throw new IllegalStateException("Too many migration adapters");
        ENTRIES.put(key, new Entry(owner, path, converter));
    }
    public static List<String> keys() { thread(); return ENTRIES.entrySet().stream().filter(e -> e.getValue().owner.isEnabled()).map(Map.Entry::getKey).toList(); }
    public static Report run(String id) {
        thread(); Entry e = ENTRIES.get(id);
        if (e == null || !e.owner.isEnabled()) throw new IllegalArgumentException("Migration adapter unavailable");
        try {
            Path real = e.source.toRealPath();
            if (!real.startsWith(e.owner.getDataFolder().toPath().toRealPath()) || Files.size(real) > 8_388_608)
                throw new IllegalArgumentException("Migration source is outside plugin folder or exceeds 8 MiB");
            byte[] snapshot;
            try (var input = Files.newInputStream(real)) { snapshot = input.readNBytes(8_388_609); }
            if (snapshot.length > 8_388_608) throw new IllegalArgumentException("Migration source grew beyond 8 MiB");
            return Objects.requireNonNull(e.converter.convert(snapshot.clone()));
        } catch (Exception ex) {
            ProviderFailures.report("MigrationPreviews", e.owner.getName(), id, ex);
            return new Report(0, 0, Map.of(), Map.of(), List.of(ex.getClass().getSimpleName()));
        }
    }
    public static void unregister(Plugin owner) { thread(); ENTRIES.values().removeIf(e -> e.owner == owner); }
    private static void thread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Migration previews require server thread"); }
}
