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
 * Optional, skippable first-use guide for language, menu, and provider-confirmed feature milestones.
 * <p>Preferred access: {@code api.gui().onboarding()}.
 */
public final class CoreOnboarding {
    private static final String LANGUAGE = "onboarding.languagechosen";
    private static final String FIRST_MENU = "onboarding.firstmenuopened";
    private static final String PLUGIN_MENU = "onboarding.pluginfunctionconfirmed";
    private static final String SKIPPED = "onboarding.skipped";
    private static final String GUIDE_MENU = "first-steps";
    private static final String SETTING = "guide";

    private static Plugin activeCore;

    private CoreOnboarding() {}

    /** Server-thread hook used by the managed menu registry; inactive before registration. */
    public static void markMenu(Player player, String menuKey) { markMenu(activeCore, player, menuKey); }

    /** Releases the Core reference on shutdown. */
    public static void stop() { activeCore = null; }

    private static NamespacedKey key(Plugin core, String name) { return new NamespacedKey(core, name); }
    private static boolean enabled(Plugin core, Player player) { return core != null && core.isEnabled() && player != null && player.isOnline(); }
    private static Vystorm.vystorm_core.lang.Lang lang() { return Localization.get().core(); }

    /** Records an explicit language selection. Opening the language settings alone does not complete this task. */
    public static void markLanguage(Plugin core, Player player) {
        if (enabled(core, player)) write(core, player, read(core, player).languageChosenNow());
    }

    /** Records a successfully opened managed menu; it does not prove a plugin action was completed. */
    public static void markMenu(Plugin core, Player player, String menuKey) {
        if (!enabled(core, player) || menuKey == null || menuKey.isBlank()) return;
        String coreNamespace = new NamespacedKey(core, "onboarding").getNamespace();
        String normalized = menuKey.toLowerCase(Locale.ROOT);
        if (normalized.equals(GUIDE_MENU) || normalized.equals(coreNamespace + ":" + GUIDE_MENU)) return;
        write(core, player, read(core, player).menuOpened());
    }

    /** Since 0.26.5: call on the server thread only after a participating plugin action succeeds. */
    public static void markFeature(Plugin owner, Player player) {
        if (owner == null || owner == activeCore || !owner.isEnabled() || !enabled(activeCore, player)) return;
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Onboarding progress requires the server thread");
        write(activeCore, player, read(activeCore, player).featureConfirmed());
    }

    /** Registers the explicit /vmenu entry and a Core player setting to skip or reset the guide. Call once at enable. */
    public static void register(Plugin core, API api) {
        activeCore = core;
        api.gui().registerMenu(core, GUIDE_MENU,
                player -> lang().text(player, "onboarding.menu-title"), Material.COMPASS,
                Player::isOnline, player -> open(core, api, player), "",
                player -> lang().text(player, "onboarding.menu-description"));

        LinkedHashMap<String, String> choices = new LinkedHashMap<>();
        choices.put("show", lang().ref("onboarding.setting-show"));
        choices.put("skip", lang().ref("onboarding.setting-skip"));
        Setting guideChoice = Setting.choice(SETTING, lang().ref("onboarding.setting-label"), lang().ref("onboarding.setting-description"),
                choices, player -> read(core, player).skipped() ? "skip" : "show",
                (player, value) -> {
                    if ("skip".equals(value)) skip(core, player);
                    else reset(core, player);
                    return null;
                });
        api.settings().register(core, SettingsSection.player("onboarding", lang().ref("onboarding.settings-title"),
                lang().ref("onboarding.settings-description"), List.of(guideChoice)));
    }

    /** Opens the guide only when the user chooses its menu entry. */
    public static void open(Plugin core, API api, Player player) {
        if (!enabled(core, player)) return;
        Progress state = read(core, player);
        var lang = lang();
        List<DialogBody> body = List.of(DialogBody.plainMessage(lang.component(player, "onboarding.intro")),
                DialogBody.plainMessage(taskLine(lang, player, "onboarding.task-language", state.languageChosen())),
                DialogBody.plainMessage(taskLine(lang, player, "onboarding.task-first-menu", state.firstMenuOpened())),
                DialogBody.plainMessage(taskLine(lang, player, "onboarding.task-plugin-menu", state.pluginFunctionOpened())),
                state.skipped() ? DialogBody.plainMessage(lang.component(player, "onboarding.skipped-status"))
                        : DialogBody.plainMessage(lang.component(player, "onboarding.continue-hint")));
        List<DialogButton> buttons = List.of(
                new DialogButton(lang.component(player, "onboarding.open-language"), (viewer, response) -> {
                    String key = new NamespacedKey(core, "language").toString();
                    if (!api.settings().openSection(viewer, key)) api.settings().openOverview(viewer);
                }),
                new DialogButton(lang.component(player, "onboarding.open-menus"), (viewer, response) -> api.gui().openDirectory(viewer, 0)),
                new DialogButton(lang.component(player, "onboarding.skip"), (viewer, response) -> {
                    skip(core, viewer);
                    api.dialogs().close(viewer);
                }));
        api.dialogs().open(core, player, new DialogRequest(lang.component(player, "onboarding.title"), body, List.of(), buttons,
                viewer -> enabled(core, viewer), () -> {}));
    }

    /** Clears the skip marker and all milestones so the guide starts fresh. */
    public static void reset(Plugin core, Player player) {
        if (!enabled(core, player)) return;
        write(core, player, read(core, player).resetNow());
    }

    /** Explicitly skips the guide without falsely completing any milestone. */
    public static void skip(Plugin core, Player player) {
        if (enabled(core, player)) write(core, player, read(core, player).skipNow());
    }

    static Component taskLine(Vystorm.vystorm_core.lang.Lang lang, Player player, String key, boolean complete) {
        return lang.component(player, key, "status", lang.component(player, complete ? "onboarding.done" : "onboarding.todo"));
    }

    private static Progress read(Plugin core, Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        return new Progress(bit(pdc, key(core, LANGUAGE)), bit(pdc, key(core, FIRST_MENU)),
                bit(pdc, key(core, PLUGIN_MENU)), bit(pdc, key(core, SKIPPED)));
    }

    private static void write(Plugin core, Player player, Progress progress) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        setBit(pdc, key(core, LANGUAGE), progress.languageChosen());
        setBit(pdc, key(core, FIRST_MENU), progress.firstMenuOpened());
        setBit(pdc, key(core, PLUGIN_MENU), progress.pluginFunctionOpened());
        setBit(pdc, key(core, SKIPPED), progress.skipped());
    }

    private static boolean bit(PersistentDataContainer pdc, NamespacedKey key) {
        Byte value = pdc.get(key, PersistentDataType.BYTE);
        return value != null && value != 0;
    }

    private static void setBit(PersistentDataContainer pdc, NamespacedKey key, boolean enabled) {
        if (enabled) pdc.set(key, PersistentDataType.BYTE, (byte) 1);
        else pdc.remove(key);
    }

    record Progress(boolean languageChosen, boolean firstMenuOpened, boolean pluginFunctionOpened, boolean skipped) {
        Progress languageChosenNow() { return new Progress(true, firstMenuOpened, pluginFunctionOpened, skipped); }
        Progress menuOpened() { return new Progress(languageChosen, true, pluginFunctionOpened, skipped); }
        Progress featureConfirmed() { return new Progress(languageChosen, firstMenuOpened, true, skipped); }
        Progress skipNow() { return new Progress(languageChosen, firstMenuOpened, pluginFunctionOpened, true); }
        Progress resetNow() { return new Progress(false, false, false, false); }
        boolean complete() { return skipped || languageChosen && firstMenuOpened && pluginFunctionOpened; }
    }
}
