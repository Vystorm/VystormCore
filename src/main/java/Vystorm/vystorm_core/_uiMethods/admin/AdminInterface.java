package Vystorm.vystorm_core._uiMethods.admin;

import org.bukkit.plugin.Plugin;
import java.util.List;
import java.util.Optional;

/** Runtime-only extension point for an owner's curated dashboard module. */
public interface AdminInterface {
    void register(Plugin owner, AdminModule module);
    void unregister(Plugin owner);
    List<AdminModule> modules();
    Optional<Plugin> owner(String moduleId);
    void clear();

    /** Update check sources of plugins ({@code lifecycle.UpdateSources}). @since 0.64.0 */
    default Vystorm.vystorm_core.lifecycle.UpdateSourcesInterface updates() { return Vystorm.vystorm_core.lifecycle.UpdateSourcesInterface.DEFAULT; }

    /** Feature diagnostics shown to admins ({@code lifecycle.FeatureDiagnostics}). @since 0.64.0 */
    default Vystorm.vystorm_core.lifecycle.FeatureDiagnosticsInterface diagnostics() { return Vystorm.vystorm_core.lifecycle.FeatureDiagnosticsInterface.DEFAULT; }

    /** The admin action runner: preview, copy as command, confirm, audit log, vanilla dispatch ({@code actions.ActionsInterface}). @since 0.73.0 */
    default Vystorm.vystorm_core.actions.ActionsInterface actions() { return Vystorm.vystorm_core.actions.ActionsInterface.DEFAULT; }
}
