package Vystorm.vystorm_core._integrations.luckpermsIntegration;

import org.bukkit.entity.Player;

import java.util.UUID;

public interface LuckpermsInterface {
/**
 * Gives back an true if the user has  the permission in any form of star from back to front
 *
 * @param permission  permission should be set up in this manner "plugin.function.nameofthething
 * @param playerUUID
 * @return
 */
public boolean permissionChecker(String permission, UUID playerUUID);
	public boolean permissionChecker(String permission, Player player);
	public 	String  InventoryNametoPermission(String inventoryName);
	public int permissionleve();
}
