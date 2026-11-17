package Vystorm.vystorm_core._integrations.luckpermsIntegration;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Berechtigungsprüfung über die Bukkit-Permission-API (funktioniert mit LuckPerms und jedem anderen
 * Permission-Plugin, nutzt aber keine LuckPerms-spezifische API). Neben dem exakten Knoten zählen {@code *},
 * OP und Wildcards entlang des Pfads: für {@code a.b.c} also {@code a.*} und {@code a.b.*}.
 */
public class DefaultluckpermsHandler implements LuckpermsInterface {
 // Legacy: level-based permission checks are not implemented.
@Override
public boolean permissionChecker(String permission, java.util.UUID playerUUID) {
     if (permission == null || playerUUID == null) return false;
     Player player = Bukkit.getPlayer(playerUUID);
     if (player == null) {
	  DebugMessages.low("[DefaultluckpermsHandler] Permission check for offline player denied: " + permission);
	  return false;
     }
     return permissionChecker(permission, player);
}

@Override
public boolean permissionChecker(String permission, Player player) {
     if (permission == null || player == null) return false;
     if (player.hasPermission(permission) || player.hasPermission("*") || player.isOp()) return true;
     String[] parts = permission.split("\\.");
     StringBuilder prefix = new StringBuilder();
     for (int i = 0; i < parts.length - 1; i++) {
	  prefix.append(parts[i]).append('.');
	  if (player.hasPermission(prefix + "*")) return true;
     }
     DebugMessages.low("[DefaultluckpermsHandler] Permission denied: " + permission);
     return false;
}

@Override
public String InventoryNametoPermission(String inventoryName) {
      return inventoryName.replaceAll("[()\\[\\]\\s0-9]", "");
}

/**
 * @return immer 0; Level-basierte Berechtigungen sind nicht implementiert.
*/
@Override
public int permissionleve() {
     DebugMessages.mid("[DefaultluckpermsHandler] Permission level requested; not implemented");
     return 0;
}
}
