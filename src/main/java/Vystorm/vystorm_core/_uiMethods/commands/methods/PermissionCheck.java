package Vystorm.vystorm_core._uiMethods.commands.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.main;
import org.bukkit.entity.Player;

/** Befehlsrechte: Konsole darf alles; Spieler brauchen den Knoten, {@code *} oder eine Wildcard entlang des Pfads ({@code a.*}, {@code a.b.*}). */
public class PermissionCheck {
    public boolean check(String caster, String permission) {
	  	if (caster.equals("CONSOLE")) {
	  	    return true; // Console has all permissions
	  	}
		  Player player = main.getPlugin().getServer().getPlayer(caster);
		  if (player == null) {
			  DebugMessages.mid("[PermissionCheck] Command permission check rejected: player not found");
              return false; // Player not found
		  }
		  if (player.hasPermission("*")) return true;
		  if (permission == null) return false;
		  if (player.hasPermission(permission)) return true;
		  String[] parts = permission.split("\\.");
		  StringBuilder prefix = new StringBuilder();
		  for (int i = 0; i < parts.length - 1; i++) {
			  prefix.append(parts[i]).append('.');
			  if (player.hasPermission(prefix + "*")) return true;
		  }
	  DebugMessages.low("[PermissionCheck] Command permission denied");
      return false;
    }
}
