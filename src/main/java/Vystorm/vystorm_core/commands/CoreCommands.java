package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Central command system of Vystorm Core (capability {@code commands}, since 0.45.0): plugins declare a
 * {@link CommandSpec} tree and Core registers it, localizes subcommand names, keyword arguments, usage and help per
 * player, adds localized root aliases from the language files and lists every command in {@code /vhelp}.
 *
 * <pre>{@code
 * Lang lang = CoreApi.api().orElseThrow().lang().register(this);
 * CoreCommands.register(this, lang, CommandSpec.command("rails")
 *         .permission("vystormrails.use")
 *         .sub("map", s -> s.playerOnly().executes(ctx -> NetworkMenu.open(this, ctx.player())))
 *         .build());
 * }</pre>
 *
 * <p><b>Labels are global.</b> Minecraft has one command tree per server, so root labels cannot differ per player:
 * the English label always exists, localized root aliases ({@code commands.<cmd>.aliases}) are registered for the
 * languages the admin enables ({@link AliasPolicy}) and only when the label is free. Subcommands, keyword values,
 * tab completion, usage and help are per player: every installed language's names are accepted, completion and help
 * use the player's language.
 *
 * <p>Call {@link #register} in {@code onEnable} (Paper's Brigadier API is used then; later calls use the Bukkit
 * command map). Do not declare the command in plugin.yml as well. To keep a plugin.yml command and its executor, use
 * {@link #adopt}. Core removes a disabled plugin's commands. Server thread.
 *
 * <p>Preferred access: {@code api.command()}.
 *
 * @since 0.45.0
 */
public final class CoreCommands {
    private CoreCommands() {}

    /**
     * Registers a command tree for {@code plugin}. {@code lang} is the plugin's bundle ({@code null}: the registered
     * bundle of the plugin, registering one if needed). Registering the same label again replaces the earlier tree.
     */
    public static CommandRegistration register(Plugin plugin, Lang lang, CommandSpec spec) {
        return Registry.INSTANCE.register(plugin, lang, spec);
    }

    /**
     * Migration adapter: wraps the existing plugin.yml command {@code declaration.name()} of {@code plugin}. The old
     * executor and tab completer keep working; Core adds localized subcommand names (translated to the English ids
     * before the old executor sees them), localized keyword values, localized tab completion for the declared levels
     * (merged with the old completer's), generated {@code help}, {@code /vhelp} entries and localized root aliases.
     * Declared nodes with their own {@link CommandSpec.Builder#executes handler} run that handler instead.
     *
     * @throws IllegalArgumentException if plugin.yml has no such command
     */
    public static CommandRegistration adopt(JavaPlugin plugin, Lang lang, CommandSpec declaration) {
        return Registry.INSTANCE.adopt(plugin, lang, declaration);
    }

    /** Removes one registration (its Bukkit entries and aliases; a Paper root answers "not available" until restart). */
    public static void unregister(CommandRegistration registration) {
        if (registration != null) Registry.INSTANCE.unregister(registration);
    }

    /** Removes every registration of a plugin (Core does this when the plugin is disabled). */
    public static void unregister(Plugin plugin) {
        if (plugin != null) Registry.INSTANCE.unregister(plugin);
    }

    /** All registrations, in registration order. */
    public static List<CommandRegistration> registrations() { return Registry.INSTANCE.all(); }

    /** Registrations of one plugin. */
    public static List<CommandRegistration> registrations(Plugin plugin) {
        return Registry.INSTANCE.all().stream().filter(r -> r.plugin() == plugin).toList();
    }

    /** The registration that owns a label (English label, fixed alias or active localized alias). */
    public static Optional<CommandRegistration> find(String label) {
        if (label == null) return Optional.empty();
        String l = label.toLowerCase(Locale.ROOT);
        int colon = l.indexOf(':');
        String bare = colon >= 0 ? l.substring(colon + 1) : l;
        for (CommandRegistration r : Registry.INSTANCE.all())
            if (r.label().equals(bare) || r.fixedAliases().contains(bare) && !r.fixedAliasConflicts().containsKey(bare)
                    || r.activeAliases().contains(bare)) return Optional.of(r);
        return Optional.empty();
    }

    /** The current localized-alias policy ({@code config.yml} {@code commands.localized-aliases}). */
    public static AliasPolicy aliasPolicy() { return Settings.current().policy(); }

    /** Re-applies localized root aliases, e.g. after language files were edited. */
    public static void refreshAliases() { Registry.INSTANCE.applyAll(); }

    // ------------------------------------------------------------------ Core internal

    /**
     * Core: reads the settings, registers {@code /vhelp}, re-sends a player's command tree when their language changes
     * ({@code PlayerLanguageChangeEvent}) and applies localized aliases on the first tick (server fully started).
     */
    public static void start(JavaPlugin core) {
        configure(core.getConfig().getConfigurationSection("commands"));
        CommandOverview.register(core);
        core.getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onLanguage(Vystorm.vystorm_core.lang.PlayerLanguageChangeEvent event) {
                // Labels are global, but completion, help and the client's command tree follow the new language at once.
                if (event.isLanguageChanged()) event.getPlayer().updateCommands();
            }
        }, core);
        core.getServer().getScheduler().runTask(core, CoreCommands::serverLoaded);
    }

    /** Core: reads {@code commands:} from config.yml. */
    public static void configure(ConfigurationSection section) {
        Settings.set(Settings.of(section));
        if (Registry.INSTANCE.serverLoaded()) Registry.INSTANCE.applyAll();
    }

    /** Core: the server finished starting; apply localized aliases from now on. */
    public static void serverLoaded() { Registry.INSTANCE.markServerLoaded(); }
}
