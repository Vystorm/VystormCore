package Vystorm.vystorm_core.commands;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

/** A Bukkit command-map entry that runs a registration: the root (Bukkit mount) or one localized alias. */
final class TreeCommand extends Command implements PluginIdentifiableCommand {
    private final CommandRegistration registration;

    TreeCommand(String label, List<String> aliases, CommandRegistration registration, String description) {
        super(label, description == null ? "" : description, "/" + label, aliases);
        this.registration = registration;
        setPermission(registration.spec().permission());
    }

    CommandRegistration registration() { return registration; }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        registration.execute(sender, label, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return registration.suggest(sender, alias, args);
    }

    @Override public Plugin getPlugin() { return registration.plugin(); }
}
