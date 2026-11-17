package Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types;

import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.time.LocalDateTime;

public class High {
    public void debug(String message, LocalDateTime date) {
        java.util.logging.Logger.getLogger("Minecraft").warning("[HIGH] " + message);
        String formatted = ChatColor.RED + "[HIGH] " + message;
        if (Bukkit.isPrimaryThread()) {
            broadcast(formatted);
        } else {
            Plugin plugin = main.getPlugin();
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> broadcast(formatted));
            }
        }
    }

    private void broadcast(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("vystormCore.debug")) player.sendMessage(message);
        }
    }
}
