package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Central settings registry. Plugins register sections; Core renders them as native dialogs under
 * /vmenu → Einstellungen (also reachable from the pause menu). Values stay owned by the plugin.
 */
public interface SettingsInterface {
    void register(Plugin owner, SettingsSection section);
    void unregister(Plugin owner);
    /** Sections the player may open for the scope, in registration order. */
    List<SettingsSection> sections(Player player, SettingScope scope);
    /** Overview of personal sections plus, for admins, the server settings entry. */
    void openOverview(Player player);
    void openScope(Player player, SettingScope scope);
    /** Opens one section by {@code owner:id} (or unique {@code id}); false if missing or not allowed. */
    boolean openSection(Player player, String key);
    /** {@code owner:id} keys of all sections the player may open. */
    List<String> keys(Player player);
    void clear();

    /**
     * Tells open settings views (Core's web page {@code /app/settings}, in-game overlay) that values changed outside
     * Core's dialogs and web page, e.g. through an own command or menu, so they reload. {@code player} = whose personal
     * values changed; {@code null} = server-wide values. Saves through the dialogs or the web page report themselves.
     * Any thread is fine for the default registry; a no-op for replacements that do not support it.
     *
     * @since 0.24.0
     */
    default void changed(Player player) {}

    /** Whole plugin YAML files in the admin menu, feature switches, change history ({@link ConfigEditor}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config() { return Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT; }

    /** Personal preferences stored on the player ({@link PlayerPrefs}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface prefs() { return Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface.DEFAULT; }
}
