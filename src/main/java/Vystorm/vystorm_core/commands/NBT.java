package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * {@code /nbt <key> <value>}: setzt einen Core-Tag auf das Item in der Haupthand und listet danach alle Tags.
 * Nur mit {@value #PERMISSION}, weil Core-Tags (z. B. Interactor-Tags wie {@code openMenu}) Aktionen auslösen.
 */
public class NBT  implements CommandExecutor, org.bukkit.command.TabCompleter {
public static final String PERMISSION = "vystorm_core.admin";

@Override
public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
	var lang = Vystorm.vystorm_core.lang.Localization.get().core();
	if (!(commandSender instanceof Player player)) {
		commandSender.sendMessage(lang.text(commandSender, "nbt.players-only"));
		return true;
	}
	if (!player.hasPermission(PERMISSION)) {
		player.sendMessage(lang.text(player, "nbt.no-permission"));
		return true;
	}
	if (strings.length < 2) return false;
	try {
		ItemStack hand = player.getInventory().getItemInMainHand();
		player.getInventory().setItemInMainHand(main.getApi().inventoryInteractor().setNBTforItem(hand, strings[0], strings[1]));
	} catch (Exception failure) {
		DebugMessages.high("[NBT] Item tag command failed: " + failure.getClass().getSimpleName());
	}
	ItemStack hand = player.getInventory().getItemInMainHand();
	player.sendMessage(lang.text(player, "nbt.set", "value", String.valueOf(main.getApi().inventoryInteractor().getNBTforItem(hand))));
	ArrayList<String> keys = main.getApi().inventoryInteractor().getAllNamespacedKeys(hand);
	for (String key : keys) {
		player.sendMessage(lang.text(player, "nbt.entry", "key", key, "value", String.valueOf(main.getApi().inventoryInteractor().getValueForItem(hand, key))));
	}
	return true;
}

/** Built-in interactor tags; {@code openMenu} suggests menu keys. Nothing without {@value #PERMISSION}. */
@Override
public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
	if (!(sender instanceof Player player) || !player.hasPermission(PERMISSION) || args.length == 0) return java.util.List.of();
	java.util.List<String> out = new java.util.ArrayList<>();
	if (args.length == 1) out.addAll(java.util.List.of("openMenu", "openBook"));
	else if (args.length == 2 && args[0].equalsIgnoreCase("openMenu")) out.addAll(main.getApi().gui().menuKeys(player));
	String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
	return out.stream().filter(k -> k.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
}
}
