package Vystorm.vystorm_core._uiMethods.menu;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.function.Consumer;

/**
 * Manager view of {@link CompatMenuButtons}, reached through {@code api.dialogs().buttons()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CompatMenuButtons}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CompatMenuButtonsInterface {
    /** The delegating default. Plugins use {@code api.dialogs().buttons()}. */
    CompatMenuButtonsInterface DEFAULT = new CompatMenuButtonsInterface() {};

    /** Returns to the Core main menu (/vmenu). */
    default DialogButton home() { return CompatMenuButtons.home(); }

    /** Returns to a Core main-menu category. */
    default DialogButton category(MenuCategory category) { return CompatMenuButtons.category(category); }

    /** See {@link CompatMenuButtons#back}. */
    default DialogButton back(Consumer<Player> target) { return CompatMenuButtons.back(target); }

    /** Opens the Core settings section {@code owner:id} (see SettingsInterface). */
    default DialogButton settings(String sectionKey) { return CompatMenuButtons.settings(sectionKey); }

    /** {@code label} may be a {@code Lang.ref} reference. */
    default DialogButton settings(String sectionKey, String label, Component tooltip) { return CompatMenuButtons.settings(sectionKey, label, tooltip); }

    /** @since 0.22.0 */
    default DialogButton settings(String sectionKey, Component label, Component tooltip) { return CompatMenuButtons.settings(sectionKey, label, tooltip); }
}
