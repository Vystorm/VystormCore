package Vystorm.vystorm_core._uiMethods.menu;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Shared navigation buttons so every native menu offers the same way back.
 * <p>Preferred access: {@code api.dialogs().buttons()}.
 */
public final class CompatMenuButtons {
    private CompatMenuButtons() {}

    /** Labels are rendered in each viewer's language when Core shows the dialog. */
    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }

    /** Returns to the Core main menu (/vmenu). */
    public static DialogButton home() {
        return new DialogButton(lang().translatable("menu.home"), (p, r) -> Vystorm.vystorm_core.main.getApi().gui().openDirectory(p, 0));
    }

    /** Returns to a Core main-menu category. */
    public static DialogButton category(MenuCategory category) {
        return new DialogButton(Component.text("↑ ").append(lang().translatable(category.langKey() + ".title")), (p, r) -> Vystorm.vystorm_core.main.getApi().gui().openDirectory(p, 0, category.group()));
    }

    public static DialogButton back(Consumer<Player> target) {
        return new DialogButton(lang().translatable("menu.back"), (p, r) -> target.accept(p));
    }

    /** Opens the Core settings section {@code owner:id} (see SettingsInterface). */
    public static DialogButton settings(String sectionKey) { return settings(sectionKey, lang().translatable("menu.settings-button"), null); }

    /** {@code label} may be a {@code Lang.ref} reference. */
    public static DialogButton settings(String sectionKey, String label, Component tooltip) {
        return settings(sectionKey, Vystorm.vystorm_core.lang.Localization.get().deferred(label), tooltip);
    }

    /** @since 0.22.0 */
    public static DialogButton settings(String sectionKey, Component label, Component tooltip) {
        return new DialogButton(label, tooltip, (p, r) -> {
            if (!Vystorm.vystorm_core.main.getApi().settings().openSection(p, sectionKey))
                lang().send(p, "common.settings-unavailable");
        });
    }
}
