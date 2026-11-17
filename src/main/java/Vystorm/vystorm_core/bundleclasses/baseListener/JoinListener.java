package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {
		API api = main.getApi();

		@EventHandler
	    	public void onPlayerJoin(PlayerJoinEvent event) {
			     api.player().loadPlayer(event.getPlayer().getUniqueId());
	       	 // Code to handle player join event
	   	 }
}
