package Vystorm.vystorm_core._uiMethods.tutorial;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Manager view of {@link CoreOnboarding}, reached through {@code api.gui().onboarding()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CoreOnboarding}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CoreOnboardingInterface {
    /** The delegating default. Plugins use {@code api.gui().onboarding()}. */
    CoreOnboardingInterface DEFAULT = new CoreOnboardingInterface() {};

    /** Since 0.26.5: call on the server thread only after a participating plugin action succeeds. */
    default void markFeature(Plugin owner, Player player) { CoreOnboarding.markFeature(owner, player); }
}
