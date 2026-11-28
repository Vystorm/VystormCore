package Vystorm.vystorm_core.knownplayers.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * The personal setting "Others can find me by name when I am offline" (owner decision 2, default on) in Core's
 * {@code /vsettings}, section Privacy. The choice is kept in the known-player store, so it also holds while the
 * player is offline, which is exactly when it matters. Core-internal.
 */
final class KnownPlayersSettings {
    static final String SECTION = "privacy";
    static final String SETTING = "findable-offline";

    private KnownPlayersSettings() {}

    static void register(Plugin core) {
        try {
            Lang lang = Localization.get().core();
            Vystorm.vystorm_core.main.getApi().settings().register(core, SettingsSection.player(SECTION, lang.ref("settings.privacy.title"),
                    lang.ref("settings.privacy.description"),
                    List.of(Setting.toggle(SETTING, lang.ref("settings.privacy.findable-offline"), lang.ref("settings.privacy.findable-offline-hint"),
                            p -> KnownPlayers.findable(p.getUniqueId()),
                            (p, on) -> { KnownPlayers.setFindable(p.getUniqueId(), on); return null; }))));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[KnownPlayers] Privacy setting not registered", failure);
        }
    }
}
