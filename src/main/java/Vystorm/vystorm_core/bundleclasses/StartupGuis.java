package Vystorm.vystorm_core.bundleclasses;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.itemstack.ItemConstructor;
import org.bukkit.ChatColor;

public class StartupGuis {
	public  void register(API api){
		    api.gui().addMenu("Players[0]");
		    api.gui().addMenu("Plugins[0]");
		    api.gui().addMenu("Admin[0]");
		    api.gui().addMenu("Settings[0]");
		    addPlayerItemToMain(api);
		    addPluginsItemToMain(api);
		    addPersonalItemToMain(api);
		    addAdminItemToMain(api);
		    addSettingsItemToMain(api);
		    api.inventoryInteractor().register(new Vystorm.vystorm_core._advancedMethods.inventoryInteractor.entrys.openMenu());
            api.inventoryInteractor().register(new Vystorm.vystorm_core._advancedMethods.inventoryInteractor.entrys.openBook());
            // Legacy chest menus stay reachable as /vmenu MainMenu; the main menu is the native category directory.
            Vystorm.vystorm_core._uiMethods.menu.CoreMenus.register(api);
            // Core-Einstellungen als natives Panel (/vsettings mit Client-Mod ≥ 0.8.0); ersetzt ein Plugin api.settings(), bleibt es beim Dialog.
            try {
                Vystorm.vystorm_core.panel.internal.SettingsPanels.register(Vystorm.vystorm_core.main.getPlugin(),
                        (Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry) api.settings());
            } catch (RuntimeException failure) {
                Vystorm.vystorm_core.main.getPlugin().getLogger().warning("[Panels] settings panel not registered: " + failure.getMessage());
            }
            try {
                Vystorm.vystorm_core.panel.internal.MenuPanels.register(Vystorm.vystorm_core.main.getPlugin(), api.gui());
            } catch (RuntimeException failure) {
                Vystorm.vystorm_core.main.getPlugin().getLogger().warning("[Panels] menu panel not registered: " + failure.getMessage());
            }
            try {
                Vystorm.vystorm_core.lang.internal.LanguagePanel.register(Vystorm.vystorm_core.main.getPlugin());
            } catch (RuntimeException failure) {
                Vystorm.vystorm_core.main.getPlugin().getLogger().warning("[Panels] language panel not registered: " + failure.getMessage());
            }
            DebugMessages.mid("[StartupGuis] Startup menus and navigation registered");

	}

private void addPersonalItemToMain(API api) {
		ItemConstructor item = new ItemConstructor();
		item.setMaterial("PLAYER_HEAD");
		item.setName("§aPersonal");
		item.addLore("§7Click to view personal things");
		item.setFunction("openMenu", "Personal[0]");
		api.gui().addItem("MainMenu", item.build());
		api.gui().addMenu("Personal[0]");
	}

private void addPlayerItemToMain(API api){
		    ItemConstructor item = new ItemConstructor();
		    item.setMaterial("PLAYER_HEAD");
		    item.setName("§aPlayers");
		    item.addLore("§7Click to view players");
		    item.setFunction("openMenu","Players[0]");
		    api.gui().addItem("MainMenu", item.build());
		    api.gui().addMenu("Players[0]");
		    };
	private void addPluginsItemToMain(API api){
		    ItemConstructor item = new ItemConstructor();
		    item.setMaterial("COMMAND_BLOCK");
		    item.setName(ChatColor.GOLD + "Plugins");
		    item.addLore("§7Click to view all plugins under vcore");
		    item.setFunction("openMenu", "Plugins[0]");
		    api.gui().addItem("MainMenu", item.build());
		    api.gui().addMenu("Plugins[0]");
		    };
	private void addAdminItemToMain(API api){
		    ItemConstructor item = new ItemConstructor();
		    item.setMaterial("NETHER_STAR");
		    item.setName("§aAdmin");
		    item.addLore("§7Click to view admin commands");
		    item.setPermission("menu.admin");
		    item.setFunction("openMenu","Admin[0]");
		    api.gui().addItem("MainMenu", item.build());
		    api.gui().addMenu("Admin[0]");
	}
	private void addSettingsItemToMain(API api){
		    ItemConstructor item = new ItemConstructor();
		    item.setMaterial("REDSTONE");
		    item.setName("§aSettings");
		    item.addLore("§7Click to view settings");
		    item.setPermission("menu.settings");
		    item.setFunction("openMenu","Settings[0]");
		    api.gui().addItem("MainMenu", item.build());
		    api.gui().addMenu("Settings[0]");
	}
	}
