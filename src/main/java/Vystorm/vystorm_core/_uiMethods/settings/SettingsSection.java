package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A group of settings shown as one native dialog. {@code afterSave} runs once after at least one value
 * changed (e.g. save the config file and reload the owner's runtime state).
 */
public record SettingsSection(String id, String title, String description, SettingScope scope, String permission,
                              List<Setting> settings, Consumer<Player> afterSave) {
    public static final int MAX_SETTINGS = 48;

    public SettingsSection {
        if (id == null || !id.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("invalid section id: " + id);
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        Objects.requireNonNull(scope, "scope");
        permission = permission == null ? "" : permission;
        settings = List.copyOf(Objects.requireNonNull(settings, "settings"));
        if (settings.isEmpty() || settings.size() > MAX_SETTINGS) throw new IllegalArgumentException("section needs 1.." + MAX_SETTINGS + " settings");
        if (new HashSet<>(settings.stream().map(Setting::id).toList()).size() != settings.size())
            throw new IllegalArgumentException("duplicate setting ids in section " + id);
        afterSave = afterSave == null ? p -> {} : afterSave;
    }

    public static SettingsSection player(String id, String title, String description, List<Setting> settings) {
        return new SettingsSection(id, title, description, SettingScope.PLAYER, "", settings, null);
    }

    public static SettingsSection server(String id, String title, String description, String permission,
                                         List<Setting> settings, Consumer<Player> afterSave) {
        return new SettingsSection(id, title, description, SettingScope.SERVER, permission, settings, afterSave);
    }

    boolean allowed(Player player) {
        if (!permission.isBlank() && !player.hasPermission(permission)) return false;
        if (scope == SettingScope.SERVER && permission.isBlank() && !player.hasPermission("vystorm_core.admin")) return false;
        return settings.stream().anyMatch(s -> s.allowed(player));
    }
}
