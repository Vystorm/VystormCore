package Vystorm.vystorm_core._uiMethods.commands;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._uiMethods.commands.entrys.CommandEntry;
import Vystorm.vystorm_core._uiMethods.commands.methods.PermissionCheck;
import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;

public class DefaultCommandHandler implements CommandInterface{
HashMap<Plugin, HashMap<String, CommandEntry>> firstLevel = new HashMap<>();
HashMap<Plugin, HashMap<String, HashMap<String, CommandEntry>>> secondLevel = new HashMap<>();
HashMap<Plugin, HashMap<String, HashMap<String, HashMap<String, CommandEntry>>>> thirdLevel = new HashMap<>();
HashMap<Plugin, HashMap<String, HashMap<String, HashMap<String, HashMap<String, CommandEntry>>>>> fourthLevel = new HashMap<>();
PermissionCheck pc = new PermissionCheck();
//Boolean for internal check if an update of the command menu is needed (e.g. new command registered)
private Boolean updated = false;
//Map for the command menu gets regenerated for each player use to be consistent with permissions
HashMap<String , Inventory> commandMenuCache = new HashMap<>();






@Override
@SuppressWarnings("deprecation")
public void registerCommand(Plugin plugin, String command, CommandEntry entry) {
	    firstLevelCheck(plugin, command);
	    firstLevel.get(plugin).put(command, entry);
	    DebugMessages.mid("[DefaultCommandHandler] Registered command: " + command + " for plugin: " + plugin.getName());
	    updated = false;

}

@Override
@SuppressWarnings("deprecation")
public void registerCommand(Plugin plugin, String command, String Subcommand, CommandEntry entry) {
	    if (firstLevelCheck(plugin, command)){
			  secondLevelCheck(plugin, command, Subcommand);
			  secondLevel.get(plugin).get(command).put(Subcommand, entry);
			  DebugMessages.mid("[DefaultCommandHandler] Registered subcommand: " + Subcommand + " for command: " + command + " for plugin: " + plugin.getName());
			  updated = false;

	    }

}


@Override
@SuppressWarnings("deprecation")
public void registerCommand(Plugin plugin, String command, String subcommand, String thirdCommand, CommandEntry entry) {
	    if (secondLevelCheck(plugin, command, subcommand)){
			  thirdLevelCheck(plugin, command, subcommand, thirdCommand);
			  thirdLevel.get(plugin).get(command).get(subcommand).put(thirdCommand, entry);
			  DebugMessages.mid("[DefaultCommandHandler] Registered third command: " + thirdCommand + " for subcommand: " + subcommand + " for command: " + command + " for plugin: " + plugin.getName());
			  updated = false;

	    }

}

@Override
@SuppressWarnings("deprecation")
public void registerCommand(Plugin plugin, String command, String subcommand, String thirdCommand, String fourthCommand, CommandEntry entry) {
	    if (thirdLevelCheck(plugin, command, subcommand, thirdCommand)){
			  fourthLevelCheck(plugin, command, subcommand, thirdCommand, fourthCommand);
			  fourthLevel.get(plugin).get(command).get(subcommand).get(thirdCommand).put(fourthCommand, entry);
			  DebugMessages.mid("[DefaultCommandHandler] Registered fourth command: " + fourthCommand + " for third command: " + thirdCommand + " for subcommand: " + subcommand + " for command: " + command + " for plugin: " + plugin.getName());
			  updated = false;

	    }

}

@Override
@SuppressWarnings("deprecation")
public void runCommand(Plugin plugin, String command, String[] args,String caster) {
	    if (!pc.check(plugin.toString()+"."+command , caster))return;
	    if (!firstLevelCheck(plugin,command)){
		DebugMessages.high("[DefaultCommandHandler] Command not found: "+ command + " | | " + plugin );
		return;
	    }

    DebugMessages.low("[DefaultCommandHandler] Dispatching registered command: " + command);

firstLevel.get(plugin).get(command).run(args);
}

@Override
@SuppressWarnings("deprecation")
public void runCommand(Plugin plugin, String command, String subCommand, String[] args,String caster) {
	    if (!pc.check(plugin.toString()+"."+command+"."+subCommand , caster))return;
	    if (!secondLevelCheck(plugin,command,subCommand)){
			  DebugMessages.high("[DefaultCommandHandler] Command not found: "+ command + " | | " + subCommand + " | | " + plugin );
			  return;
	    }

    DebugMessages.low("[DefaultCommandHandler] Dispatching registered command: " + command);

secondLevel.get(plugin).get(command).get(subCommand).run(args);
}

@Override
@SuppressWarnings("deprecation")
public void runCommand(Plugin plugin, String command, String subCommand, String thirdCommand, String[] args,String caster) {
	    if (!pc.check(plugin.toString()+"."+command+"."+subCommand+"."+thirdCommand , caster))return;
	    if (!thirdLevelCheck(plugin,command,subCommand,thirdCommand)){
			  DebugMessages.high("[DefaultCommandHandler] Command not found: "+ command + " | | " + subCommand + " | | " + thirdCommand + " | | " + plugin );
			  return;
	    }

    DebugMessages.low("[DefaultCommandHandler] Dispatching registered command: " + command);

thirdLevel.get(plugin).get(command).get(subCommand).get(thirdCommand).run(args);
}

@Override
@SuppressWarnings("deprecation")
public void runCommand(Plugin plugin, String command, String subCommand, String thirdCommand, String fourthCommand, String[] args,String caster) {
	    if (!pc.check(plugin.toString()+"."+command+"."+subCommand+"."+thirdCommand+"."+fourthCommand , caster))return;
	    if (!fourthLevelCheck(plugin,command,subCommand,thirdCommand,fourthCommand)){
			  DebugMessages.high("[DefaultCommandHandler] Command not found: "+ command + " | | " + subCommand + " | | " + thirdCommand + " | | " + fourthCommand + " | | " + plugin );
			  return;
	    }

    DebugMessages.low("[DefaultCommandHandler] Dispatching registered command: " + command);

fourthLevel.get(plugin).get(command).get(subCommand).get(thirdCommand).get(fourthCommand).run(args);
}

@Override
@SuppressWarnings("deprecation")
public HashMap<String, Inventory> getCommandsMenu(Player player) {
        DebugMessages.mid("[DefaultCommandHandler] Command GUI requested; not implemented");

	    return null;
}


private boolean firstLevelCheck(Plugin plugin, String command) {
	    if ( !firstLevel.containsKey(plugin) ) {
			    firstLevel.put(plugin, new HashMap<>());
			    return false;
	    }
	    return firstLevel.get(plugin).containsKey(command);
}
private boolean secondLevelCheck(Plugin plugin, String command, String subcommand) {
	    if ( !secondLevel.containsKey(plugin) ) {
			  secondLevel.put(plugin, new HashMap<>());
	    }
	    if ( !secondLevel.get(plugin).containsKey(command) ) {
			  secondLevel.get(plugin).put(command, new HashMap<>());
	    }
	    return secondLevel.get(plugin).get(command).containsKey(subcommand);
}
private boolean thirdLevelCheck(Plugin plugin, String command, String subcommand, String thirdCommand) {
	    if ( !thirdLevel.containsKey(plugin) ) {
			  thirdLevel.put(plugin, new HashMap<>());
	    }
	    if ( !thirdLevel.get(plugin).containsKey(command) ) {
			  thirdLevel.get(plugin).put(command, new HashMap<>());
	    }
	    if ( !thirdLevel.get(plugin).get(command).containsKey(subcommand) ) {
			  thirdLevel.get(plugin).get(command).put(subcommand, new HashMap<>());
	    }
	    return thirdLevel.get(plugin).get(command).get(subcommand).containsKey(thirdCommand);
}
private boolean fourthLevelCheck(Plugin plugin, String command, String subcommand, String thirdCommand, String fourthCommand) {
	    if ( !fourthLevel.containsKey(plugin) ) {
			  fourthLevel.put(plugin, new HashMap<>());
	    }
	    if ( !fourthLevel.get(plugin).containsKey(command) ) {
			  fourthLevel.get(plugin).put(command, new HashMap<>());
	    }
	    if ( !fourthLevel.get(plugin).get(command).containsKey(subcommand) ) {
			  fourthLevel.get(plugin).get(command).put(subcommand, new HashMap<>());
	    }
	    if ( !fourthLevel.get(plugin).get(command).get(subcommand).containsKey(thirdCommand) ) {
			  fourthLevel.get(plugin).get(command).get(subcommand).put(thirdCommand, new HashMap<>());
	    }
	    return fourthLevel.get(plugin).get(command).get(subcommand).get(thirdCommand).containsKey(fourthCommand);
}
}
