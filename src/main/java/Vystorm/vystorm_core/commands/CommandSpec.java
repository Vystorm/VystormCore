package Vystorm.vystorm_core.commands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Declaration of a command tree: a root command (its English label) with subcommands, arguments, permissions and
 * handlers. Immutable; build with {@link #command(String)} and register with {@link CoreCommands#register}.
 *
 * <pre>{@code
 * CommandSpec rails = CommandSpec.command("rails")
 *         .permission("vystormrails.use")
 *         .aliases("vrails", "cart")                     // fixed aliases (always active, like plugin.yml)
 *         .sub("dest", s -> s.aliases("destination").playerOnly()
 *                 .arg(Arg.greedy("name").optional().suggests((ctx, prefix) -> stations(ctx.player())))
 *                 .executes(ctx -> destination(ctx.player(), ctx.<String>optional("name").orElse(null))))
 *         .sub("reload", s -> s.permission("vystormrails.admin").executes(ctx -> reload(ctx.sender())))
 *         .build();
 * }</pre>
 *
 * <p><b>Language keys</b> (plugin bundle, default prefix {@code commands.<label>}, see docs/API.md "Commands"):
 * {@code commands.rails.description}, {@code .aliases} (localized root aliases, list), {@code .usage},
 * {@code .sub.dest.name} (localized subcommand name), {@code .sub.dest.aliases}, {@code .sub.dest.description},
 * {@code .sub.dest.usage}, {@code .sub.dest.args.name} (argument display name), {@code commands.rails.values.<key>}.
 *
 * <p>A node without handler but with subcommands shows its help. Ids are lower-case {@code a-z 0-9 _ -}.
 *
 * @since 0.45.0
 */
public final class CommandSpec {
    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final boolean playerOnly, hidden, async;
    private final Duration cooldown;
    private final List<Arg<?>> args;
    private final Map<String, CommandSpec> children;
    private final CommandHandler handler;
    private final String description, usage;
    private final String langPrefix;
    private final boolean help;
    private CommandSpec parent;
    private String path;

    private CommandSpec(Builder b, String langPrefix) {
        this.name = b.name;
        this.aliases = List.copyOf(b.aliases);
        this.permission = b.permission;
        this.playerOnly = b.playerOnly;
        this.hidden = b.hidden;
        this.async = b.async;
        this.cooldown = b.cooldown;
        this.args = List.copyOf(b.args);
        this.handler = b.handler;
        this.description = b.description;
        this.usage = b.usage;
        this.langPrefix = langPrefix;
        this.help = b.help;
        Map<String, CommandSpec> kids = new LinkedHashMap<>();
        for (Builder child : b.children.values()) {
            CommandSpec node = new CommandSpec(child, null);
            node.parent = this;
            kids.put(node.name, node);
        }
        this.children = Collections.unmodifiableMap(kids);
    }

    /** Starts a root command; {@code name} is the English label (always registered). */
    public static Builder command(String name) { return new Builder(name, true); }

    // ------------------------------------------------------------------ accessors

    /** English id: the label for the root, the canonical subcommand name otherwise. */
    public String name() { return name; }

    /** Fixed (not localized) aliases: root aliases for the root, extra accepted names for subcommands. */
    public List<String> aliases() { return aliases; }

    /** Permission node or {@code null} (inherits nothing: each node checks only its own). */
    public String permission() { return permission; }

    public boolean playerOnly() { return playerOnly; }

    /** Not listed in help and tab completion (still runs). */
    public boolean hidden() { return hidden; }

    /** Handler runs off the server thread (parsing and checks still on it). */
    public boolean async() { return async; }

    /** Per-player cooldown, {@code null} for none. */
    public Duration cooldown() { return cooldown; }

    public List<Arg<?>> args() { return args; }

    /** Subcommands by English id, in declaration order. */
    public Map<String, CommandSpec> children() { return children; }

    public CommandHandler handler() { return handler; }

    /** English fallback description when the language files have none, may be {@code null}. */
    public String description() { return description; }

    /** English fallback usage (arguments part) when the language files have none, may be {@code null}. */
    public String usage() { return usage; }

    /** Parent node, {@code null} for the root. */
    public CommandSpec parent() { return parent; }

    public boolean isRoot() { return parent == null; }

    public CommandSpec root() { CommandSpec n = this; while (n.parent != null) n = n.parent; return n; }

    /** Whether {@code /<label> help} is generated (root only; default true when there are subcommands). */
    public boolean helpEnabled() { return root().help && !root().children.isEmpty(); }

    /** Language key prefix of this node: {@code commands.rails}, {@code commands.rails.sub.dest}, ... */
    public String langPath() {
        if (path == null) path = parent == null ? langPrefix : parent.langPath() + ".sub." + name;
        return path;
    }

    /** English ids from the root's first subcommand to this node (empty for the root). */
    public List<String> idPath() {
        List<String> out = new ArrayList<>();
        for (CommandSpec n = this; n.parent != null; n = n.parent) out.add(0, n.name);
        return out;
    }

    /** Whether running this node does something (handler or, for the root of an adapter, the old executor). */
    public boolean runnable() { return handler != null; }

    @Override public String toString() { return "CommandSpec[" + (parent == null ? "/" : "") + String.join(" ", idPath().isEmpty() ? List.of(name) : idPath()) + "]"; }

    // ------------------------------------------------------------------ builder

    /** Builder of a root or subcommand node. Not thread-safe; build on one thread. */
    public static final class Builder {
        private final String name;
        private final boolean root;
        private final List<String> aliases = new ArrayList<>();
        private String permission;
        private boolean playerOnly, hidden, async, help = true;
        private Duration cooldown;
        private final List<Arg<?>> args = new ArrayList<>();
        private final Map<String, Builder> children = new LinkedHashMap<>();
        private CommandHandler handler;
        private String description, usage, langPrefix;

        private Builder(String name, boolean root) {
            this.name = Arg.checkId(name == null ? null : name.toLowerCase(Locale.ROOT));
            this.root = root;
        }

        /** Fixed aliases (always accepted, not localized). For the root they are registered like plugin.yml aliases. */
        public Builder aliases(String... names) {
            if (names != null) for (String a : names) {
                String alias = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
                if (alias.isEmpty() || alias.contains(" ")) throw new IllegalArgumentException("Bad alias '" + a + "' of " + name);
                if (!alias.equals(name) && !aliases.contains(alias)) aliases.add(alias);
            }
            return this;
        }

        public Builder permission(String node) { this.permission = node == null || node.isBlank() ? null : node; return this; }

        public Builder playerOnly() { this.playerOnly = true; return this; }

        public Builder hidden() { this.hidden = true; return this; }

        /** Runs the handler with the async scheduler; the handler must not touch the world. */
        public Builder async() { this.async = true; return this; }

        public Builder cooldown(Duration duration) { this.cooldown = duration == null || duration.isZero() || duration.isNegative() ? null : duration; return this; }

        /** Adds an argument; only the last may be greedy, optional arguments only after the required ones. */
        public Builder arg(Arg<?> arg) {
            Objects.requireNonNull(arg, "arg");
            if (!args.isEmpty()) {
                Arg<?> last = args.get(args.size() - 1);
                if (last.kind() == Arg.Kind.GREEDY) throw new IllegalArgumentException(name + ": nothing may follow greedy argument " + last.id());
                if (last.isOptional() && !arg.isOptional()) throw new IllegalArgumentException(name + ": required " + arg.id() + " after optional " + last.id());
            }
            for (Arg<?> a : args) if (a.id().equals(arg.id())) throw new IllegalArgumentException(name + ": duplicate argument " + arg.id());
            args.add(arg);
            return this;
        }

        /** Adds a subcommand (English id); configure it in {@code body}. */
        public Builder sub(String id, Consumer<Builder> body) {
            Builder child = new Builder(id, false);
            if (children.containsKey(child.name)) throw new IllegalArgumentException(name + ": duplicate subcommand " + child.name);
            if (body != null) body.accept(child);
            children.put(child.name, child);
            return this;
        }

        public Builder executes(CommandHandler handler) { this.handler = handler; return this; }

        /** English fallback description (used when no language file has {@code <node>.description}). */
        public Builder description(String text) { this.description = text; return this; }

        /** English fallback for the arguments part of the usage line (no tags, e.g. {@code "NAME [AMOUNT]"}). */
        public Builder usage(String text) { this.usage = text; return this; }

        /** Root only: language key prefix instead of {@code commands.<label>}. */
        public Builder lang(String prefix) {
            if (!root) throw new IllegalStateException("lang prefix only on the root");
            this.langPrefix = prefix == null || prefix.isBlank() ? null : prefix.trim();
            return this;
        }

        /** Root only: {@code false} switches off the generated {@code help} subcommand. */
        public Builder help(boolean enabled) {
            if (!root) throw new IllegalStateException("help only on the root");
            this.help = enabled;
            return this;
        }

        public CommandSpec build() {
            if (!root) throw new IllegalStateException("build() on the root builder");
            checkNames(this);
            return new CommandSpec(this, langPrefix == null ? "commands." + name : langPrefix);
        }

        private static void checkNames(Builder node) {
            Map<String, String> seen = new LinkedHashMap<>();
            for (Builder child : node.children.values()) {
                for (String n : concat(child.name, child.aliases)) {
                    String before = seen.putIfAbsent(n, child.name);
                    if (before != null && !before.equals(child.name))
                        throw new IllegalArgumentException(node.name + ": '" + n + "' names both " + before + " and " + child.name);
                }
                checkNames(child);
            }
        }

        private static List<String> concat(String first, List<String> rest) {
            List<String> out = new ArrayList<>(rest.size() + 1);
            out.add(first);
            out.addAll(rest);
            return out;
        }
    }
}
