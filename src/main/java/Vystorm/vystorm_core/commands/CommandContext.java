package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * One command invocation (or tab completion): sender, the label used, the matched node and the parsed arguments.
 *
 * @since 0.45.0
 */
public final class CommandContext {
    private final CommandRegistration registration;
    private final CommandSender sender;
    private final String label;
    private final String[] raw;
    private final CommandSpec node;
    private final Map<String, Object> values;
    private final Map<String, String> inputs;

    CommandContext(CommandRegistration registration, CommandSender sender, String label, String[] raw, CommandSpec node,
                   Map<String, Object> values, Map<String, String> inputs) {
        this.registration = registration;
        this.sender = sender;
        this.label = label;
        this.raw = raw;
        this.node = node;
        this.values = values;
        this.inputs = inputs;
    }

    public CommandSender sender() { return sender; }

    public boolean isPlayer() { return sender instanceof Player; }

    /** The sender as player; for {@link CommandSpec.Builder#playerOnly()} nodes always set. */
    public Player player() {
        if (sender instanceof Player p) return p;
        throw new CommandException("command.error.player-only");
    }

    /** The label typed (English name, a fixed or a localized alias). */
    public String label() { return label; }

    /** The typed arguments after the label, unchanged. */
    public String[] rawArgs() { return raw.clone(); }

    /** The node that runs. */
    public CommandSpec node() { return node; }

    /** English subcommand ids from the root to {@link #node()}. */
    public List<String> path() { return node.idPath(); }

    public Plugin plugin() { return registration.plugin(); }

    public CommandRegistration registration() { return registration; }

    /** The plugin's language bundle. */
    public Lang lang() { return registration.lang(); }

    /** The sender's language code in the plugin's bundle. */
    public String language() { return registration.lang().language(sender); }

    /** Parsed value of an argument; {@code null} if an optional one was left out. */
    @SuppressWarnings("unchecked")
    public <T> T get(String id) { return (T) values.get(id); }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> optional(String id) { return Optional.ofNullable((T) values.get(id)); }

    @SuppressWarnings("unchecked")
    public <T> T getOr(String id, T fallback) { Object v = values.get(id); return v == null ? fallback : (T) v; }

    public boolean has(String id) { return values.containsKey(id); }

    /** The text typed for an argument, {@code null} if absent. */
    public String input(String id) { return inputs.get(id); }

    /** All parsed values by argument id. */
    public Map<String, Object> values() { return Collections.unmodifiableMap(values); }

    /** Sends a text of the plugin's bundle to the sender. */
    public void reply(String key, Object... placeholders) { registration.lang().send(sender, key, placeholders); }

    /** A {@link CommandException} to throw: {@code throw ctx.fail("no-cart");}. */
    public CommandException fail(String key, Object... placeholders) { return new CommandException(key, placeholders); }

    /**
     * Which of the English keyword {@code keys} the typed {@code input} stands for (English or any installed language,
     * see {@link Arg#choice}), or {@code null}. For free-text arguments that also accept a keyword, e.g.
     * {@code /rails dest clear} = {@code /schienen ziel löschen}.
     */
    public String keyword(String input, String... keys) {
        if (input == null || keys == null) return null;
        String t = input.trim().toLowerCase(java.util.Locale.ROOT);
        Names names = registration.names();
        String language = language();
        for (String key : keys) if (names.acceptedValues(node.root(), key, language).contains(t)) return key;
        return null;
    }

    /** A keyword's name in the sender's language ({@code clear} → {@code löschen}), for suggestions and texts. */
    public String valueName(String key) { return registration.names().value(node.root(), key, language()); }

    /** The localized usage line of the running node for the sender, e.g. {@code /schienen ziel [NAME]}. */
    public String usage() { return registration.usage(sender, label, node); }

    CommandContext withValue(String id, Object value, String input) {
        Map<String, Object> v = new LinkedHashMap<>(values);
        Map<String, String> i = new LinkedHashMap<>(inputs);
        v.put(id, value);
        i.put(id, input);
        return new CommandContext(registration, sender, label, raw, node, v, i);
    }
}
