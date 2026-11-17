package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;

public class LeaveListener implements Listener {
		@EventHandler
		public void onPlayerLeave(PlayerQuitEvent event) throws IOException {
			    main.getApi().player().unloadPlayer(event.getPlayer().getUniqueId());
		}
}
