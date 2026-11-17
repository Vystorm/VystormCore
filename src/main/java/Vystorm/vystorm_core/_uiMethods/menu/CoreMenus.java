package Vystorm.vystorm_core._uiMethods.menu;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Builds the native Core main menu: categories, settings, tutorials, administration and the pause-menu entry. */
public final class CoreMenus {
    private static final String MENU_FILE = "menu.yml";
    private CoreMenus() {}

    public static void register(API api) {
        Plugin core = Vystorm.vystorm_core.main.getPlugin();
        var gui = api.gui();
        for (MenuCategory category : List.of(MenuCategory.CHARACTER, MenuCategory.ECONOMY, MenuCategory.COMMUNITY,
                MenuCategory.WORLD, MenuCategory.BUILDING)) category(api, core, category);
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        gui.registerMenu(core, "settings", lang.ref("menu.settings.title"), Material.COMPARATOR, p -> true,
                p -> {
                    // Natives Panel (ohne Mod: dessen fallback = openOverview); ist es nicht registriert, wie bisher der Dialog.
                    var panels = Vystorm.vystorm_core.panel.Panels.of(core);
                    if (Vystorm.vystorm_core.panel.internal.PanelRegistry.find(panels.id("settings")).isPresent()) panels.open(p, "settings");
                    else api.settings().openOverview(p);
                }, "", lang.ref("menu.settings.description"));
        Vystorm.vystorm_core.lang.CoreLanguage.registerSettings(api, core);
        // Server selector: only visible while connected to a proxy hub (network module).
        try { Vystorm.vystorm_core.network.paper.ServerSelectorPage.register(core, api); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[CoreMenus] Server selector not registered", failure); }
        category(api, core, MenuCategory.TUTORIALS);
        category(api, core, MenuCategory.ADMIN);

        File file = new File(core.getDataFolder(), MENU_FILE);
        YamlConfiguration loaded = AtomicFiles.readYaml(file.toPath(), core.getLogger());
        // unreadable: moved aside, defaults in memory, never saved over until the next restart
        boolean broken = loaded == null;
        YamlConfiguration menu = broken ? new YamlConfiguration() : loaded;
        menu.addDefault("pause-menu", true);
        // Language of the ESC-menu data pack: auto = language.default if fixed, else keep the installed pack's language, else en.
        menu.addDefault("pause-menu-language", "auto");
        menu.options().copyDefaults(true);
        if (!broken) save(menu, file);
        // Core loads at STARTUP, before the worlds; the level folder is known from the first tick on.
        org.bukkit.Bukkit.getScheduler().runTask(core, () -> PauseMenuPack.sync(core, menu.getBoolean("pause-menu"), menu.getString("pause-menu-language", "auto")));
        api.settings().register(core, SettingsSection.server("menu", "Vystorm Core", lang.ref("settings.menu.description"), "vystorm_core.admin",
                List.of(Setting.toggle("pause-menu", lang.ref("settings.menu.pause-menu"),
                        lang.ref("settings.menu.pause-menu-hint"),
                        p -> menu.getBoolean("pause-menu"), (p, v) -> { menu.set("pause-menu", v); return null; })),
                p -> {
                    if (!broken) save(menu, file);
                    else DebugMessages.high("[CoreMenus] " + MENU_FILE + " was unreadable at startup; the change applies until the next restart but is not saved");
                    if (PauseMenuPack.sync(core, menu.getBoolean("pause-menu"), menu.getString("pause-menu-language", "auto")))
                        lang.send(p, "settings.menu.restart");
                }));
        if (api.settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry)
            registry.policy(core, "menu", "pause-menu", new Vystorm.vystorm_core._uiMethods.settings.SettingPolicy(
                    Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART, true));
        DebugMessages.mid("[CoreMenus] Main menu categories, settings and pause-menu entry registered");
    }

    private static void category(API api, Plugin core, MenuCategory category) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        api.gui().registerMenu(core, category.key(), lang.ref(category.langKey() + ".title"), category.icon(),
                (Player p) -> api.gui().hasVisibleMenus(p, category.group()),
                p -> api.gui().openDirectory(p, 0, category.group()), "", lang.ref(category.langKey() + ".description"));
    }

    private static void save(YamlConfiguration menu, File file) {
        try { AtomicFiles.write(file.toPath(), menu); }
        catch (IOException failure) { DebugMessages.high("[CoreMenus] " + MENU_FILE + " could not be saved: " + failure.getMessage()); }
    }
}
