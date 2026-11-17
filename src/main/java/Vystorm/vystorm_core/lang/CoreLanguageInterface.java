package Vystorm.vystorm_core.lang;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manager view of {@link CoreLanguage}, reached through {@code api.lang().coreLanguage()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CoreLanguage}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CoreLanguageInterface {
    /** The delegating default. Plugins use {@code api.lang().coreLanguage()}. */
    CoreLanguageInterface DEFAULT = new CoreLanguageInterface() {};

    /**
     * Re-builds the option list of {@code /vsettings} → Language from {@link Localization#selectableLanguages()}
     * (after plugins registered bundles or a reload). Server thread.
     */
    default void refreshSettings() { CoreLanguage.refreshSettings(); }

    /** The language's own name ({@code language.name} of any bundle, else Java's name of the locale in itself). */
    default String nativeName(String code) { return CoreLanguage.nativeName(code); }

    /** The player's stored choice: a code or {@code auto}. */
    default String choice(Player player) { return CoreLanguage.choice(player); }

    /**
     * Stores the player's language choice ({@code auto} or a code) exactly like {@code /language} and
     * {@code /vsettings} do (player data {@code vystorm_core:pref.language}), switches Core's texts and fires
     * {@link PlayerLanguageChangeEvent}. Works while {@code language.force} is on (the choice then applies once force is
     * switched off). Server thread only. Returns {@code false} if Core is not running.
     *
     * @since 0.45.0
     */
    default boolean setChoice(Player player, String codeOrAuto, PlayerLanguageChangeEvent.Cause cause) {
        return CoreLanguage.setChoice(player, codeOrAuto, cause);
    }

    /**
     * {@code /vadmin language reload}: re-reads Core's {@code language.*} settings and every bundle, refreshes the
     * settings options and fires {@link PlayerLanguageChangeEvent} ({@code RELOAD}) for players whose language changed.
     * Server thread. Returns the number of bundles.
     */
    default int reload() { return CoreLanguage.reload(); }
}
