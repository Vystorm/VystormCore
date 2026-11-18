package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manager view of {@link CoreCommands}, reached through {@code api.command()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link CoreCommands}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface CoreCommandsInterface {
    /** The delegating default. Plugins use {@code api.command()}. */
    CoreCommandsInterface DEFAULT = new CoreCommandsInterface() {};

    /**
     * Registers a command tree for {@code plugin}. {@code lang} is the plugin's bundle ({@code null}: the registered
     * bundle of the plugin, registering one if needed). Registering the same label again replaces the earlier tree
     * (0.65.0: after startup on Paper inside the existing registration, so the Paper node keeps working and returns
     * the same registration; fixed root aliases stay as first registered until a restart).
     */
    default CommandRegistration register(Plugin plugin, Lang lang, CommandSpec spec) { return CoreCommands.register(plugin, lang, spec); }

    /**
     * Migration adapter: wraps the existing plugin.yml command {@code declaration.name()} of {@code plugin}. The old
     * executor and tab completer keep working; Core adds localized subcommand names (translated to the English ids
     * before the old executor sees them), localized keyword values, localized tab completion for the declared levels
     * (merged with the old completer's), generated {@code help}, {@code /vhelp} entries and localized root aliases.
     * Declared nodes with their own {@link CommandSpec.Builder#executes handler} run that handler instead.
     *
     * @throws IllegalArgumentException if plugin.yml has no such command
     */
    default CommandRegistration adopt(JavaPlugin plugin, Lang lang, CommandSpec declaration) { return CoreCommands.adopt(plugin, lang, declaration); }

    /** Removes one registration (its Bukkit entries and aliases; a Paper root answers "not available" until restart). */
    default void unregister(CommandRegistration registration) { CoreCommands.unregister(registration); }

    /** Removes every registration of a plugin (Core does this when the plugin is disabled). */
    default void unregister(Plugin plugin) { CoreCommands.unregister(plugin); }

    /** All registrations, in registration order. */
    default List<CommandRegistration> registrations() { return CoreCommands.registrations(); }

    /** Registrations of one plugin. */
    default List<CommandRegistration> registrations(Plugin plugin) { return CoreCommands.registrations(plugin); }

    /** The registration that owns a label (English label, fixed alias or active localized alias). */
    default Optional<CommandRegistration> find(String label) { return CoreCommands.find(label); }

    /** The current localized-alias policy ({@code config.yml} {@code commands.localized-aliases}). */
    default AliasPolicy aliasPolicy() { return CoreCommands.aliasPolicy(); }

    /** Re-applies localized root aliases, e.g. after language files were edited. */
    default void refreshAliases() { CoreCommands.refreshAliases(); }

    /**
     * Sends every online player their command list again, e.g. after a reload in which a feature switch changed
     * (suggestions and help follow {@link CommandSpec.Builder#visibleWhen visibleWhen} by themselves). Inside
     * {@link CommandBatch#run} once at the end; any thread.
     *
     * @since 0.65.0
     */
    default void refresh() { CoreCommands.refresh(); }
}
