package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import org.bukkit.entity.Player;

public interface ReplacerEntity {
     //Replacers have to start with :: and end with ::
     public String getReplacer();
     public String run(Player player);
}
