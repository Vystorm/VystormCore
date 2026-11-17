package Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types;

import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import java.time.LocalDateTime;

public class Schutdown {
    public void debug(String message, LocalDateTime date) {
        java.util.logging.Logger.getLogger("Minecraft").severe("[SHUTDOWN] " + message);
        String formatted = ChatColor.DARK_RED + "[SHUTDOWN] " + message;
        Runnable shutdown = () -> {
            for (var player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("vystormCore.debug")) player.sendMessage(formatted);
            }
            Bukkit.shutdown();
        };
        if (Bukkit.isPrimaryThread()) shutdown.run();
        else {
            var plugin = main.getPlugin();
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, shutdown);
        }
    }
}
