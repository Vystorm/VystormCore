package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/**
 * A command tree registered through {@link CoreCommands}: what was registered, how, and which localized aliases are
 * active. {@link #execute} and {@link #suggest} run the tree directly (menus, admin panels, tests).
 *
 * @since 0.45.0
 */
public final class CommandRegistration {
    /** How the root label reached the server. */
    public enum Mount {
        /** Paper's Brigadier lifecycle API ({@code LifecycleEvents.COMMANDS}, {@code BasicCommand}). */
        PAPER,
        /** Bukkit's command map (registered after startup, or {@code commands.registration: bukkit}). */
        BUKKIT,
        /** An existing plugin.yml command whose executor Core wraps ({@link CoreCommands#adopt}). */
        ADAPTER,
        /** Not (yet) on the server: before the Paper event fired, or after unregistering. */
        NONE
    }

    /** State of one localized root alias. */
    public enum AliasStatus {
        ACTIVE,
        /** Its language is not enabled by {@link AliasPolicy}. */
        OFF_BY_POLICY,
        /** Switched off by the admin ({@code commands.disabled-aliases}). */
        DISABLED,
        /** The label belongs to another command; Core left it alone. */
        COLLISION,
        /** Applied once the server has finished starting. */
        PENDING
    }

    /** A localized root alias, its language and state; {@code detail} names the owner of a colliding label. */
    public record Alias(String alias, String language, AliasStatus status, String detail) {}

    /** The old executor and completer an adapter delegates to. */
    record Legacy(Command command, CommandExecutor executor, TabCompleter completer) {
        void run(CommandSender sender, String label, String[] args) {
            if (executor == null) return;
            if (!executor.onCommand(sender, command, label, args)) {
                String usage = command.getUsage();
                if (usage != null && !usage.isBlank()) sender.sendMessage(usage.replace("<command>", label));
            }
        }

        List<String> complete(CommandSender sender, String label, String[] args) {
            if (completer == null) return List.of();
            List<String> out = completer.onTabComplete(sender, command, label, args);
            return out == null ? List.of() : out;
        }
    }

    private final Plugin plugin;
    private final Lang core;
    private volatile Lang lang;
    private volatile CommandSpec spec;
    private final Legacy legacy;
    private volatile Engine engine;
    private final Cooldowns cooldowns = new Cooldowns();
    volatile Mount mount = Mount.NONE;
    volatile List<String> registeredLabels = List.of();
    volatile String labelConflict;
    /** Fixed aliases another plugin's command owns → that owner (filled when aliases are applied). */
    volatile Map<String, String> fixedConflicts = Map.of();
    volatile Map<String, Alias> aliases = Map.of();

    CommandRegistration(Plugin plugin, Lang lang, CommandSpec spec, Legacy legacy, Lang core) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.legacy = legacy;
        this.core = core;
        this.engine = new Engine(this, new Names(lang, core));
    }

    /**
     * The same plugin registered the same label again after its root was mounted on Paper (0.65.0): the new tree
     * takes over inside this registration, so the Paper node (which cannot be replaced after startup) runs it.
     */
    void replace(Lang bundle, CommandSpec next) {
        Lang l = Objects.requireNonNull(bundle, "lang");
        Engine e = new Engine(this, new Names(l, core));
        this.lang = l;
        this.spec = Objects.requireNonNull(next, "spec");
        this.engine = e;
    }

    public Plugin plugin() { return plugin; }

    public Lang lang() { return lang; }

    public CommandSpec spec() { return spec; }

    /** English label. */
    public String label() { return spec.name(); }

    public Mount mount() { return mount; }

    /** Fixed aliases (spec or plugin.yml). */
    public List<String> fixedAliases() {
        if (legacy != null && legacy.command() != null) return List.copyOf(legacy.command().getAliases());
        return spec.aliases();
    }

    /** Labels the server accepted for the root (English label and fixed aliases; Paper/Bukkit report). */
    public List<String> registeredLabels() { return registeredLabels; }

    /** Owner of the English label if another command already had it ({@code null} otherwise). */
    public String labelConflict() { return labelConflict; }

    /** Fixed aliases that do not reach this tree because another plugin's command has the label, with that owner. */
    public Map<String, String> fixedAliasConflicts() { return fixedConflicts; }

    /** Localized root aliases with their state, in language order. */
    public List<Alias> localizedAliases() { return List.copyOf(aliases.values()); }

    /** Localized aliases that work right now. */
    public List<String> activeAliases() {
        List<String> out = new ArrayList<>();
        for (Alias a : aliases.values()) if (a.status() == AliasStatus.ACTIVE) out.add(a.alias());
        return Collections.unmodifiableList(out);
    }

    /** Subcommand names that select two different nodes in one language (shown in {@code /vadmin commands}). */
    public List<String> conflicts() { return engine.conflicts(); }

    /** Whether this is an adapter around a plugin.yml command. */
    public boolean adapter() { return legacy != null; }

    /** Runs the tree as if {@code sender} typed {@code /<label> <args>}. Server thread. */
    public void execute(CommandSender sender, String label, String[] args) {
        try { engine.execute(sender, label == null ? spec.name() : label, args); }
        catch (RuntimeException | LinkageError failure) {
            Vystorm.vystorm_core.lifecycle.ProviderFailures.report("commands", plugin.getName(), "/" + label, failure);
        }
    }

    /** Tab completion for the last (possibly empty) argument, localized for the sender. */
    public List<String> suggest(CommandSender sender, String label, String[] args) {
        try { return engine.suggest(sender, label == null ? spec.name() : label, args); }
        catch (RuntimeException | LinkageError failure) {
            Vystorm.vystorm_core.lifecycle.ProviderFailures.report("commands", plugin.getName(), "suggest /" + label, failure);
            return List.of();
        }
    }

    /** Sends the help page of the root. */
    public void help(CommandSender sender, String label, int page) { engine.help(sender, label == null ? spec.name() : label, spec, page); }

    /** Localized usage line of a node of this tree for the sender. */
    public String usage(CommandSender sender, String label, CommandSpec node) { return engine.usage(sender, label == null ? spec.name() : label, node); }

    /** Localized description of a node for the sender (empty if none). */
    public String description(CommandSender sender, CommandSpec node) { return engine.description(sender, node); }

    /** Help lines (node entries) the sender may use; empty if none. */
    public List<CommandSpec> entries(CommandSender sender) {
        if (!Engine.allowed(sender, spec)) return List.of();
        return engine.entries(sender, spec);
    }

    Legacy legacy() { return legacy; }

    Cooldowns cooldowns() { return cooldowns; }

    Engine engine() { return engine; }

    Names names() { return engine.names(); }

    void aliases(Map<String, Alias> states) { this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(states)); }

    @Override public String toString() { return "CommandRegistration[" + plugin.getName() + " /" + spec.name() + " " + mount + "]"; }
}
