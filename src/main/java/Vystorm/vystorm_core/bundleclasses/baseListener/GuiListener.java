package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;

public final class GuiListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) { main.getApi().gui().handleClick(e); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) { main.getApi().gui().handleDrag(e); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void open(InventoryOpenEvent e) { main.getApi().gui().handleOpen(e); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void openResult(InventoryOpenEvent e) { main.getApi().gui().handleOpenResult(e); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) { main.getApi().gui().handleClose(e); }
}
