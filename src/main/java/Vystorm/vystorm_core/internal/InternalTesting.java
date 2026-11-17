package Vystorm.vystorm_core.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.units.qual.A;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class InternalTesting implements CommandExecutor {
ArrayList<String> testArgs = new ArrayList<>();
API api;

public InternalTesting() {
     this.api = main.getApi();
     testArgs.add("gui");
     testArgs.add("");
     testArgs.add("");
}

@Override
public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        DebugMessages.low("[InternalTesting] Command requested; name=" + command.getName());
     switch (s.toLowerCase()) {
	  case "gui" -> {
	       if (!api.gui().getGui().isEmpty()) {
		    commandSender.sendMessage("Main Menu is enabled");
	       } else {
		    commandSender.sendMessage("Main Menu is disabled");
	       }
	       if (api.gui().getMenuNames().isEmpty()) {
		    commandSender.sendMessage("No additional menus found");
	       } else {
		    commandSender.sendMessage("Additional menus found: " + String.join(", ", api.gui().getMenuNames()));
	       }

	       // Add your internal testing logic here
	       return true;
	  }
	  default -> {
	       commandSender.sendMessage("Unknown argument.");
	       for (String arg : testArgs) {
		    commandSender.sendMessage(" - " + arg);
	       }

	  }
     }
	return true;
}
}
