package Vystorm.vystorm_core.bundleclasses;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.commands.Menu;
import Vystorm.vystorm_core.commands.NBT;
import Vystorm.vystorm_core.internal.InternalTesting;
import Vystorm.vystorm_core._uiMethods.admin.CoreAdminDashboard;
import Vystorm.vystorm_core.main;
import org.bukkit.plugin.Plugin;

public record CommandsSummary() {
	    public void register(main main) {
			  Menu menu = new Menu();
			  main.getCommand("menu").setExecutor(menu);
			  main.getCommand("menu").setTabCompleter(menu);
                  DebugMessages.mid("[CommandsSummary] Menu command registered");
			  NBT nbt = new NBT();
			  main.getCommand("nbt").setExecutor(nbt);
			  main.getCommand("nbt").setTabCompleter(nbt);
                  DebugMessages.mid("[CommandsSummary] Item tag command registered");
		  CoreAdminDashboard dashboard = new CoreAdminDashboard(main, main.getApi().admin());
		  main.getCommand("admin").setExecutor(dashboard);
		  main.getCommand("admin").setTabCompleter(dashboard);
		  main.getCommand("vadmin").setExecutor(dashboard);
		  main.getCommand("vadmin").setTabCompleter(dashboard);
		  var regions = new Vystorm.vystorm_core.region.RegionCommand();
		  main.getCommand("vregion").setExecutor(regions);
		  main.getCommand("vregion").setTabCompleter(regions);
		  var settingsCommand = new Vystorm.vystorm_core.commands.SettingsCommand();
		  main.getCommand("einstellungen").setExecutor(settingsCommand);
		  main.getCommand("einstellungen").setTabCompleter(settingsCommand);
		  var web = new Vystorm.vystorm_core.commands.WebCommand();
		  main.getCommand("web").setExecutor(web);
		  main.getCommand("web").setTabCompleter(web);
          var tradeChat = new Vystorm.vystorm_core.chat.TradeChatCommand();
          main.getCommand("vtrade").setExecutor(tradeChat);
          main.getCommand("vtrade").setTabCompleter(tradeChat);
          main.getServer().getPluginManager().registerEvents(tradeChat, main);
		  dashboard.registerMenu();

	    }
}
