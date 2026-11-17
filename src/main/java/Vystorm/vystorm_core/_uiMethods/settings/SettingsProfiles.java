package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Named, version-checked server profiles. Only explicitly profile-safe values are included. @since 0.26.5 */
public final class SettingsProfiles {
    private SettingsProfiles() {}
    static Path file(Path folder, String name) {
        if (name == null || !name.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid profile name");
        return folder.resolve(name + ".yml");
    }
    public static int exportProfile(SettingsRegistry registry, Player viewer, Path folder, String name) throws IOException {
        return exportProfile(registry, viewer, folder, name, null);
    }
    /** Capture selected section keys across plugins, retaining the 96-value bound. @since 0.52.0 */
    public static int exportProfile(SettingsRegistry registry, Player viewer, Path folder, String name, Set<String> selectedSections) throws IOException {
        requireAdmin(viewer);
        Path file = file(folder, name);
        if (Files.exists(file)) throw new IOException("Profile already exists; use a new name");
        List<Map<String, Object>> values = new ArrayList<>();
        for (var section : registry.view(viewer)) {
            if (section.section().scope() != SettingScope.SERVER) continue;
            if (selectedSections != null && !selectedSections.contains(section.key())) continue;
            for (var v : section.settings()) {
                if (!v.readable() || !safe(registry, section.key(), v.setting().id(), selectedSections != null)) continue;
                if (!(v.value() instanceof Boolean || v.value() instanceof Number || v.value() instanceof String)) continue;
                values.add(Map.of("section", section.key(), "setting", v.setting().id(), "version", registry.ownerVersion(section.key()), "value", v.value()));
                if (values.size() > 96) throw new IOException("Profile exceeds 96 settings");
            }
        }
        if (selectedSections != null && (values.isEmpty() || !registry.view(viewer).stream().map(SettingsRegistry.SectionView::key).collect(java.util.stream.Collectors.toSet()).containsAll(selectedSections)))
            throw new IOException("No safe values or unavailable sections selected");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1); yaml.set("values", values);
        AtomicFiles.write(file, yaml);
        return values.size();
    }
    public static SettingsDraft importProfile(SettingsRegistry registry, Player viewer, Path folder, String name) throws IOException {
        return importProfile(registry, viewer, folder, name, null);
    }
    /** Import only explicitly selected plugin names; null retains strict whole-profile import. @since 0.52.0 */
    public static SettingsDraft importProfile(SettingsRegistry registry, Player viewer, Path folder, String name,
                                              Set<String> selectedPlugins) throws IOException {
        requireAdmin(viewer);
        Set<String> selected = selectedPlugins == null ? null : selectedPlugins.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Path file = file(folder, name);
        if (Files.isSymbolicLink(file) || Files.size(file) > 262144) throw new IOException("Invalid profile file");
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file)); }
        catch (InvalidConfigurationException ex) { throw new IOException("Invalid profile YAML", ex); }
        if (yaml.getInt("format") != 1 || !yaml.isList("values")) throw new IOException("Unsupported profile format");
        List<?> raw = yaml.getList("values");
        if (raw == null || raw.stream().anyMatch(value -> !(value instanceof Map)))
            throw new IOException("Invalid profile rows");
        List<Map<?, ?>> values = yaml.getMapList("values");
        if (values.size() > 96) throw new IOException("Profile exceeds 96 settings");
        SettingsDraft draft = new SettingsDraft();
        Set<String> seen = new HashSet<>();
        for (var value : values) {
            String section = String.valueOf(value.get("section")), id = String.valueOf(value.get("setting"));
            if (selected != null && !selected.contains(section.split(":", 2)[0])) continue;
            if (!seen.add(section + "/" + id) || !safe(registry, section, id, selected != null)
                    || !registry.ownerVersion(section).equals(value.get("version"))) throw new IOException("Unsafe, duplicate or version-mismatched setting: " + section + "/" + id);
            var current = registry.view(viewer, section).filter(v -> v.section().scope() == SettingScope.SERVER)
                    .stream().flatMap(v -> v.settings().stream()).filter(v -> v.readable() && v.setting().id().equals(id)).findFirst();
            if (current.isEmpty()) throw new IOException("Setting unavailable: " + section + "/" + id);
            Object candidate = SettingsRegistry.coerce(current.get().setting(), value.get("value"));
            if (candidate == null || !SettingsRegistry.same(candidate, value.get("value"))) throw new IOException("Invalid value: " + section + "/" + id);
            draft.stage(section, id, current.get().value(), candidate);
        }
        return draft;
    }
    private static void requireAdmin(Player player) {
        if (!player.hasPermission("vystorm_core.admin")) throw new SecurityException("Admin permission required");
    }
    private static boolean safe(SettingsRegistry registry, String section, String id, boolean explicitlySelected) {
        return registry.policy(section, id).profileSafe() || explicitlySelected && registry.group(section).equals(ConfigEditor.GROUP);
    }
}
