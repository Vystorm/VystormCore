package Vystorm.vystorm_core.bundleclasses;

import Vystorm.vystorm_core.bundleclasses.baseListener.*;
import org.bukkit.Bukkit;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.plugin.Plugin;

public record Listener() {
	    public void register(Plugin plugin) {
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new JoinListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new LeaveListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new ItemInteractorListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new PlayerInteractListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new BlockBreakListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new BlockPlaceListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new EntityDamageListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new EntityDeathListener(), plugin);
			  Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) new PlayerDropItemListener(), plugin);
			  // PlayerPickupItemListener (veraltetes Event, leerer Body) wird nicht mehr registriert.
            Bukkit.getPluginManager().registerEvents(new CombatPolicyListener(), plugin);
            Bukkit.getPluginManager().registerEvents(new AdvancedListener(), plugin);
            Bukkit.getPluginManager().registerEvents(new GuiListener(), plugin);
            Bukkit.getPluginManager().registerEvents(new BukkitInventoryEventListener(), plugin);
            if (Vystorm.vystorm_core.compat.ServerCompat.hasClass("io.papermc.paper.event.player.PlayerInventorySlotChangeEvent"))
                Bukkit.getPluginManager().registerEvents(new PaperInventoryEventListener(), plugin);
            Vystorm.vystorm_core.compat.ClickActions.initialize(plugin);
            if (Vystorm.vystorm_core.compat.ServerCompat.hasDialogs()) {
                Bukkit.getPluginManager().registerEvents(new DialogListener(), plugin);
            } else {
                var handler = Vystorm.vystorm_core.singeltons.DialogSingelton.base();
                if (handler instanceof Vystorm.vystorm_core.compat.internal.LegacyDialogs legacy)
                    Bukkit.getPluginManager().registerEvents(legacy.chat, plugin);
                plugin.getLogger().info("Native dialogs unavailable; Core menus and forms use clickable chat. Pause-menu entries are unavailable; use /vmenu.");
            }
            DebugMessages.mid("[Listener] Core event listeners registered");
	    }
}
