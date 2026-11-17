package Vystorm.vystorm_core.bundleclasses.baseListener;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class DialogListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void customClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        if (Vystorm.vystorm_core._uiMethods.menu.PauseMenuPack.handle(connection.getPlayer(), event.getIdentifier())) return;
        Vystorm.vystorm_core.main.getApi().dialogs().handle(connection.getPlayer(), event.getIdentifier(), event.getDialogResponseView());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Vystorm.vystorm_core.main.getApi().dialogs().close(event.getPlayer());
    }
}
