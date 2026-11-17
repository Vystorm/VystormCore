package Vystorm.vystorm_core._uiMethods.settings;

import java.util.*;
import org.bukkit.entity.Player;

/**
 * Bounded, short-lived server-settings draft. It contains values only, never Player references.
 * Validation happens before any write; plugin setters are not transactions and may partially fail.
 * Server thread only. @since 0.26.5
 */
public final class SettingsDraft {
    public record Change(String section, String id, Object before, Object after) {}
    private final LinkedHashMap<String, Change> changes = new LinkedHashMap<>();
    private final long expiresAt = System.nanoTime() + 900_000_000_000L;
    public List<Change> changes() { return List.copyOf(changes.values()); }
    public boolean expired() { return System.nanoTime() > expiresAt; }
    public void stage(String section, String id, Object before, Object after) {
        if (expired()) throw new IllegalStateException("Draft expired");
        String key = section + "/" + id;
        Change previous = changes.get(key);
        Object baseline = previous == null ? before : previous.before();
        if (SettingsRegistry.same(baseline, after)) { changes.remove(key); return; }
        if (!changes.containsKey(key) && changes.size() >= 96) throw new IllegalStateException("Draft is full");
        changes.put(key, new Change(section, id, baseline, after));
    }
    public List<String> conflicts(SettingsRegistry registry, Player player) {
        if (expired()) return List.of("expired");
        List<String> conflicts = new ArrayList<>();
        for (Change c : changes.values()) {
            var section = registry.view(player, c.section());
            var view = section.stream().filter(s -> s.section().scope() == SettingScope.SERVER)
                    .flatMap(s -> s.settings().stream()).filter(s -> s.setting().id().equals(c.id())).findFirst();
            if (view.isEmpty() || !view.get().readable() || !SettingsRegistry.same(c.before(), view.get().value())
                    || !SettingsRegistry.same(c.after(), SettingsRegistry.coerce(view.get().setting(), c.after())))
                conflicts.add(c.section() + "/" + c.id());
        }
        return List.copyOf(conflicts);
    }
    /** Checks all baselines first, consumes the draft once, then applies each section through the existing registry. */
    public List<SettingsRegistry.SaveResult> apply(SettingsRegistry registry, Player player) {
        if (!conflicts(registry, player).isEmpty()) throw new IllegalStateException("Draft conflicts");
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Change c : changes.values()) groups.computeIfAbsent(c.section(), k -> new LinkedHashMap<>()).put(c.id(), c.after());
        changes.clear();
        List<SettingsRegistry.SaveResult> results = new ArrayList<>();
        for (var group : groups.entrySet()) results.add(registry.save(player, group.getKey(), group.getValue(), "draft"));
        return List.copyOf(results);
    }
}
