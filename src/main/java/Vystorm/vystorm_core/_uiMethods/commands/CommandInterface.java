package Vystorm.vystorm_core._uiMethods.commands;

import Vystorm.vystorm_core._uiMethods.commands.entrys.CommandEntry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;

public interface CommandInterface extends Vystorm.vystorm_core.commands.CoreCommandsInterface {
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void registerCommand	(Plugin plugin, String command, CommandEntry entry);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void registerCommand	(Plugin plugin, String command ,String Subcommand, CommandEntry entry);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void registerCommand	(Plugin plugin, String command,String subcommand,String thirdCommand, CommandEntry entry);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void registerCommand      (Plugin plugin, String command,String subcommand ,String thirdCommand,String fourthCommand, CommandEntry entry);


/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void runCommand (Plugin plugin, String command, String[] args,String caster);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void runCommand (Plugin plugin, String command, String subCommand, String[] args,String caster);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void runCommand (Plugin plugin, String command, String subCommand, String thirdCommand, String[] args,String caster);
/** @deprecated Use {@code api.command().register(plugin, lang, spec)}. */
@Deprecated(since = "0.64.0")
void runCommand (Plugin plugin, String command, String subCommand, String thirdCommand, String fourthCommand, String[] args,String caster);

//** Returns the full command menu for a player including only the commands they have permission to use. The key is the command name and the value is the inventory representing the command menu. **//
/** @deprecated Use {@code api.command().registrations()}. */
@Deprecated(since = "0.64.0")
HashMap<String , Inventory> getCommandsMenu(Player player);

}
