package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import org.bukkit.entity.Player;

import java.util.ArrayList;

public class PlaceholderReplacer {
private ArrayList<ReplacerEntity> placeholders = new ArrayList<>();

public void addPlaceholder(ReplacerEntity entity) {
    placeholders.add(entity);
    DebugMessages.mid("[PlaceholderReplacer] Placeholder registered; count=" + placeholders.size());
	return;
}
public String replacePlaceholders(String message, Player player) {
	  String result = message;
	  for (ReplacerEntity placeholder : placeholders) {
	       if (result.contains(placeholder.getReplacer())) {
		    result = result.replace(placeholder.getReplacer(), placeholder.run(player));
            DebugMessages.low("[PlaceholderReplacer] Message placeholder replaced");
	       }
	  }
	  return result;
}

public void removePlaceholder(ReplacerEntity placeholder) {
    DebugMessages.mid("[PlaceholderReplacer] Placeholder removal requested; existing removal is not implemented");
    placeholders.removeIf(p -> {
	 p.getReplacer();
	 return false;
    });
}
}
