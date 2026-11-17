package Vystorm.vystorm_core._uiMethods.autoGui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public interface GuiInteface {
	    /*
	     * Sets up a gui with the ground features of back next and main menu
	     * and the edit button to edit things directly in the gui
	     * It is given back to the use class and has to be stored there
	     */
	    boolean createGui();
	/*
	 * Adds an item to the gui with a name to identify it
	 *
	 */
	boolean addItem(String name, ItemStack target);
	/*
	 * Adds a new menu to the gui with a name to identify it
	 *
	 */
	boolean addMenu(String name);
	/*
	 * Removes an a gui  with the name
	 *
	 *
	 */
	boolean removeMenu(String name);
	boolean removeItem(String name, ItemStack target);

	/*
	 * This is used for edit mode of the gui and will have to be added with a config overlap in later versions right now it only changes the item in the slot
	 *
	 *
	 */
	boolean setSpecificItem(String menuName, Integer slot, ItemStack target);

 	HashMap<String, Inventory> getGui();

	Inventory getInventory(String name);



Inventory getInventory(String name, Player targetName);

Inventory getInventory(String name, UUID uuid);

ArrayList<String> getMenuNames();
    /** Personal inventories are released on close; null viewer keeps a shared inventory until releaseInventory. */
    default Inventory createInventory(org.bukkit.plugin.Plugin owner, org.bukkit.inventory.InventoryHolder holder,
            UUID viewer, int size, String title, GuiCallbacks callbacks) {
        throw new UnsupportedOperationException("Managed GUIs are not supported by this handler");
    }
    default void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon,
            java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener) {
        throw new UnsupportedOperationException("Menu registration is not supported by this handler");
    }
    default boolean openMenu(Player player, String key) { return false; }
    /** Register in a named directory; an empty group denotes the main directory. */
    default void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon,
            java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener, String group) {
        throw new UnsupportedOperationException("Menu groups are not supported by this handler");
    }
    /** Register with a hover description shown in /vmenu; see MenuCategory for the standard groups. */
    default void registerMenu(org.bukkit.plugin.Plugin owner, String key, String title, org.bukkit.Material icon,
            java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener, String group, String description) {
        registerMenu(owner, key, title, icon, access, opener, group);
    }
    /**
     * Register with title and description computed per viewer (e.g. {@code p -> lang.text(p, "menu.title")}); either may
     * also return a {@code Lang.ref} reference. For fixed localized texts {@code lang.ref(key)} with the String
     * overload is enough.
     *
     * @since 0.22.0
     */
    default void registerMenu(org.bukkit.plugin.Plugin owner, String key, java.util.function.Function<Player, String> title,
            org.bukkit.Material icon, java.util.function.Predicate<Player> access, java.util.function.Consumer<Player> opener,
            String group, java.util.function.Function<Player, String> description) {
        registerMenu(owner, key, title.apply(null), icon, access, opener, group, description == null ? "" : description.apply(null));
    }
    default boolean hasVisibleMenus(Player player, String group) { return false; }
    /** Einträge einer Gruppe, die der Spieler öffnen darf (aktive Plugins), in Registrierungsreihenfolge. @since 0.25.0 */
    default java.util.List<MenuEntryView> menuEntries(Player player, String group) { return java.util.List.of(); }
    /** All visible registered menus, including custom groups. Server thread only. @since 0.26.5 */
    default java.util.List<MenuEntryView> menuEntries(Player player) { return menuEntries(player, ""); }
    /** Explain every registration for an admin-selected target; reasons may include a declared permission node, so callers must enforce admin permission. @since 0.26.5 */
    default java.util.List<MenuAccess> inspectMenus(Player target) { return java.util.List.of(); }
    /** Optional denial explanation; does not grant access. Owner must already own this registration. @since 0.26.5 */
    default void menuAccessReason(org.bukkit.plugin.Plugin owner, String key, java.util.function.Function<Player, String> reason) {}
    /**
     * Declares an additional Bukkit permission required to open a registered menu. The menu's existing access
     * predicate remains in force. Passing {@code null} clears the declaration. A node may be included in menu access
     * diagnostics, so callers must restrict {@link #inspectMenus(Player)} to administrators. Older handlers ignore
     * this additive metadata method.
     *
     * @since 0.26.5
     */
    default void menuRequiredPermission(org.bukkit.plugin.Plugin owner, String menuKey, String permission) {}
    default void openDirectory(Player player, int page) {}
    default void openDirectory(Player player, int page, String group) {
        throw new UnsupportedOperationException("Menu groups are not supported by this handler");
    }
    default void unregisterMenus(org.bukkit.plugin.Plugin owner) {}
    /** Entfernt einen Eintrag, den {@code owner} mit {@code key} registriert hat; false, wenn es ihn nicht gibt. @since 0.25.1 */
    default boolean unregisterMenu(org.bukkit.plugin.Plugin owner, String key) { return false; }
    default void releaseInventory(Inventory inventory) {}
    default void clearManaged() {}
    default boolean isManaged(Inventory inventory) { return false; }
    default void handleClick(org.bukkit.event.inventory.InventoryClickEvent event) {}
    default void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {}
    default void handleOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {}
    default void handleOpenResult(org.bukkit.event.inventory.InventoryOpenEvent event) {}
    default void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {}
    default java.util.List<String> menuKeys(Player player) { return java.util.List.of(); }

    /** Native panels of the client mod with their fallbacks ({@code panel.Panels}). @since 0.64.0 */
    default Vystorm.vystorm_core.panel.PanelsInterface panels() { return Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT; }

    /** Clickable screens in the player's view ({@code screen.Screens}). @since 0.64.0 */
    default Vystorm.vystorm_core.screen.ScreensInterface screens() { return Vystorm.vystorm_core.screen.ScreensInterface.DEFAULT; }

    /** HUD corner windows ({@code screen.Huds}). @since 0.64.0 */
    default Vystorm.vystorm_core.screen.HudsInterface huds() { return Vystorm.vystorm_core.screen.HudsInterface.DEFAULT; }

    /** Entries in the menu about another player ({@code playermenu.PlayerMenu}). @since 0.64.0 */
    default Vystorm.vystorm_core.playermenu.PlayerMenuInterface playerMenu() { return Vystorm.vystorm_core.playermenu.PlayerMenuInterface.DEFAULT; }

    /** Sections on the player profile card ({@code profile.Profiles}). @since 0.64.0 */
    default Vystorm.vystorm_core.profile.ProfilesInterface profiles() { return Vystorm.vystorm_core.profile.ProfilesInterface.DEFAULT; }

    /** Recent menus and the menu explorer ({@code _uiMethods.menu.MenuExplorer}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.menu.MenuExplorerInterface menuExplorer() { return Vystorm.vystorm_core._uiMethods.menu.MenuExplorerInterface.DEFAULT; }

    /** Milestones of the first-steps guide ({@code _uiMethods.tutorial.CoreOnboarding}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboardingInterface onboarding() { return Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboardingInterface.DEFAULT; }
}
