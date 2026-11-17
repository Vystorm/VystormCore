package Vystorm.vystorm_core._uiMethods.autoGui;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory.AutoInventoryCreator;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory.ForwardingNameHandler;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory.SetMenuBar;
import Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory.functions.InvetoryTarget;
import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

public class DefaultGuiHandler implements GuiInteface {
AutoInventoryCreator ACI = new AutoInventoryCreator();
SetMenuBar SMB = new SetMenuBar();
ForwardingNameHandler FNH = new ForwardingNameHandler();

HashMap<String, Inventory> gui = new HashMap<>();
HashMap<String, InvetoryTarget> targets = new HashMap<>();

@Override
public boolean createGui() {
     gui.put("MainMenu", ACI.create("MainMenu", 54));
     DebugMessages.mid("[DGH] Main menu created");
     SMB.setMenu(gui.get("MainMenu"));
     if (!gui.containsKey("MainMenu")) {
	  DebugMessages.high("[DefaultGuiHandler] " + "MainMenu not found during creation");
	  return false;
     }
     return true;
}

@Override
public boolean addItem(String name, ItemStack target) {
     if (gui.containsKey(name)) {
	  while (gui.get(name).firstEmpty() == -1) {
	       FNH.up(name);
	       if (gui.containsKey(name)) {
	       } else {
		    addMenu(name);
	       }
	  }
	  DebugMessages.low("[DGH] Adding item to menu " + name);
	  gui.get(name).addItem(target);
     }
     return true;
}


@Override
public boolean addMenu(String name) {
     if (!gui.containsKey(name)) {
	  gui.put(name, ACI.create(name, 54));
          DebugMessages.mid("[DGH] Menu created: " + name);
	  SMB.setMenu(gui.get(name));
     }
     if (!gui.containsKey(name))
	  DebugMessages.high("[DGH] " + "Menu not found during item adding " + name);
     return true;
}

@Override
public boolean removeMenu(String name) {
     while (gui.containsKey(name)) {
	  gui.remove(name);
          DebugMessages.mid("[DGH] Menu removed: " + name);
	  FNH.up(name);
     }
     if (!gui.containsKey(name)) DebugMessages.high("[DGH] " + "Menu not found" + name);
     return true;
}

@Override
public boolean removeItem(String name, ItemStack target) {
     while (gui.containsKey(name)) {
	  if (gui.get(name).contains(target)) {
	       ;
	       gui.get(name).remove(target);
          DebugMessages.low("[DGH] Item removed from menu " + name);
	       break;
	  } else {
	       FNH.up(name);
	  }
	  //nope
     }
     if (!gui.get(name).contains(target)) {
	  DebugMessages.high("[DGH] " + "Item not found during item removing " + name);
     }
     return true;
}

@Override
public boolean setSpecificItem(String menuName, Integer slot, ItemStack target) {
     if (gui.containsKey(menuName)) {
	  gui.get(menuName).setItem(slot, target);
          DebugMessages.low("[DGH] Menu slot updated; menu=" + menuName + "; slot=" + slot);

     } else if (!gui.get(menuName).getItem(slot).equals(target))
	  DebugMessages.high("[DGH] " + "Specific Item not found" + menuName);
     return true;
}

/**
 * @return
 */
@Override
public HashMap<String, Inventory> getGui() {
     return gui;
}

/**
 * @param name
 * @return
 */
@Override
public Inventory getInventory(String name) {
     if (name == null) return null;
     if (name.startsWith("(")) {
	  var target = targets.get(name);
	  if (target == null) {
	       DebugMessages.mid("[DefaultGuiHandler] Unknown GUI target: " + name);
	       return null;
	  }
	  return target.run();
     }
     return gui.get(name);
}

@Override
public Inventory getInventory(String name, Player player) {
     if (player == null || name == null) return null;
     if (!name.equals("MainMenu") && !player.isOp()
             && !player.hasPermission("vcore.gui.access." + name)
             && !player.hasPermission("vgui.access." + name)) return null;
     InvetoryTarget target = targets.get(name);
     Inventory source = name.contains("(") && target != null ? target.run(player) : gui.get(name);
     if (source == null) return null;
     Inventory copy = createInventory(main.getPlugin(), null, player.getUniqueId(), source.getSize(), name,
             new GuiCallbacks() {
                 @Override public void click(org.bukkit.event.inventory.InventoryClickEvent event) {
                     try {
                         if (event.getClickedInventory() == Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView()))
                             main.getApi().inventoryInteractor().handleEvent(event);
                     } finally { event.setCancelled(true); }
                 }
             });
     copy.setContents(java.util.Arrays.stream(source.getContents()).map(i -> i == null ? null : i.clone())
             .toArray(ItemStack[]::new));
     return new NoPermissionInventoryStripper().strip(copy, player);
}

@Override
public Inventory getInventory(String name, UUID uuid) {
     return uuid == null ? null : getInventory(name, Bukkit.getPlayer(uuid));
}

/**
 * @return
 */
@Override
public ArrayList<String> getMenuNames() {
     return new ArrayList<>(gui.keySet());
}

private final ManagedGuiRegistry managed = new ManagedGuiRegistry();
@Override public Inventory createInventory(org.bukkit.plugin.Plugin owner, org.bukkit.inventory.InventoryHolder holder, UUID viewer, int size, String title, GuiCallbacks callbacks) { return managed.create(owner, holder, viewer, size, title, callbacks); }
@Override public void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon, java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener) { managed.register(owner, key, title, icon, access, opener); }
@Override public boolean openMenu(Player player, String key) { return managed.open(player, key); }
@Override public void openDirectory(Player player, int page) {
    // 0.61.0: "Home" buttons and plugins open the same main menu as /menu: the tile screen, when it is switched on
    // and can open; otherwise the directory as before.
    org.bukkit.plugin.Plugin core = Vystorm.vystorm_core.main.getPlugin();
    if (page == 0 && core != null && core.isEnabled() && core.getConfig().getBoolean("menu.screen", true) && org.bukkit.Bukkit.isPrimaryThread()) {
        try {
            new Vystorm.vystorm_core.screen.internal.MenuScreen(core, this).open(player, p -> managed.directory(p, 0));
            return;
        } catch (RuntimeException | LinkageError noScreens) { /* directory below */ }
    }
    managed.directory(player, page);
}
@Override public java.util.List<MenuEntryView> menuEntries(Player player, String group) { return managed.entries(player, group); }
@Override public java.util.List<MenuEntryView> menuEntries(Player player) { return managed.entries(player); }
@Override public java.util.List<MenuAccess> inspectMenus(Player player) { return managed.inspect(player); }
@Override public void menuAccessReason(org.bukkit.plugin.Plugin owner, String key, java.util.function.Function<Player, String> reason) { managed.reason(owner, key, reason); }
@Override public void menuRequiredPermission(org.bukkit.plugin.Plugin owner, String menuKey, String permission) { managed.requiredPermission(owner, menuKey, permission); }

@Override public void openDirectory(Player player, int page, String group) { managed.directory(player, page, group); }
@Override public void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon, java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener, String group) { managed.register(owner, key, title, icon, access, opener, group); }
@Override public java.util.List<String> menuKeys(Player player) { return managed.keys(player); }
@Override public void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon, java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener, String group, String description) { managed.register(owner, key, title, icon, access, opener, group, description); }
@Override public void registerMenu(org.bukkit.plugin.Plugin owner, String key, java.util.function.Function<Player, String> title, org.bukkit.Material icon, java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener, String group, java.util.function.Function<Player, String> description) { managed.register(owner, key, title, icon, access, opener, group, description); }
@Override public boolean hasVisibleMenus(Player player, String group) { return managed.hasVisible(player, group); }
@Override public boolean isManaged(Inventory inventory) { return managed.managed(inventory); }
@Override public void handleClick(org.bukkit.event.inventory.InventoryClickEvent event) { managed.click(event); }
@Override public void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) { managed.drag(event); }
@Override public void handleOpen(org.bukkit.event.inventory.InventoryOpenEvent event) { managed.open(event); }
@Override public void handleOpenResult(org.bukkit.event.inventory.InventoryOpenEvent event) { managed.openResult(event); }
@Override public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) { managed.close(event); }
@Override public void releaseInventory(Inventory inventory) { managed.release(inventory); }
@Override public void unregisterMenus(org.bukkit.plugin.Plugin owner) { managed.unregister(owner); }
@Override public boolean unregisterMenu(org.bukkit.plugin.Plugin owner, String key) { return managed.unregister(owner, key); }
@Override public void clearManaged() { managed.clear(); }
}
