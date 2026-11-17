package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Parses, completes and explains one registered command tree. Pure logic on top of {@link Names}; the mounts (Paper,
 * Bukkit, adapter) only forward sender, label and arguments. Thread contract: execution on the server thread,
 * completion on whatever thread the server completes on (only reads).
 */
final class Engine {
    /** Where a walk over the typed tokens ended. */
    record Walk(CommandSpec node, int consumed, boolean help) {}

    private final CommandRegistration registration;
    private final Names names;

    Engine(CommandRegistration registration, Names names) {
        this.registration = registration;
        this.names = names;
    }

    Names names() { return names; }

    private CommandSpec root() { return registration.spec(); }

    private String language(CommandSender sender) {
        try { return names.lang().language(sender); } catch (RuntimeException e) { return "en"; }
    }

    // ------------------------------------------------------------------ matching

    /** Follows subcommand names among the first {@code limit} tokens. */
    Walk walk(CommandSender sender, String language, String[] args, int limit) {
        CommandSpec node = root();
        int i = 0;
        if (limit > 0 && root().helpEnabled() && !names.lang().has(root().langPath() + ".sub.help.name")
                && match(node, args[0], language) == null && names.helpNames(language).contains(lower(args[0])))
            return new Walk(node, 1, true);
        while (i < limit && !node.children().isEmpty()) {
            CommandSpec child = match(node, args[i], language);
            if (child == null) break;
            node = child;
            i++;
        }
        return new Walk(node, i, false);
    }

    /** The child a token selects: own-language names first, then English names, then any installed language. */
    CommandSpec match(CommandSpec node, String token, String language) {
        if (token == null || token.isEmpty()) return null;
        String t = lower(token);
        for (CommandSpec child : node.children().values()) if (names.own(child, language).contains(t)) return child;
        for (CommandSpec child : node.children().values()) if (child.name().equals(t) || child.aliases().contains(t)) return child;
        for (CommandSpec child : node.children().values()) if (names.accepted(child, language).contains(t)) return child;
        return null;
    }

    static boolean allowed(CommandSender sender, CommandSpec node) {
        return node.permission() == null || sender.hasPermission(node.permission());
    }

    static boolean visible(CommandSender sender, CommandSpec node) {
        return allowed(sender, node) && !node.hidden() && !(node.playerOnly() && !(sender instanceof Player));
    }

    /** Whether running the node does something (own handler or the adapter's old executor). */
    boolean runnable(CommandSpec node) {
        return node.handler() != null || (registration.legacy() != null && node.children().isEmpty());
    }

    // ------------------------------------------------------------------ execution

    void execute(CommandSender sender, String label, String[] rawArgs) {
        String[] args = rawArgs == null ? new String[0] : rawArgs;
        Lang core = names.core();
        if (!registration.plugin().isEnabled()) { core.send(sender, "command.error.disabled"); return; }
        if (!allowed(sender, root())) { core.send(sender, "command.error.no-permission"); return; }
        String language = language(sender);
        Walk walk = walk(sender, language, args, args.length);
        if (walk.help()) { helpCommand(sender, label, Arrays.copyOfRange(args, 1, args.length)); return; }
        CommandSpec node = walk.node();
        for (CommandSpec n = node; n != null; n = n.parent())
            if (!allowed(sender, n)) { core.send(sender, "command.error.no-permission"); return; }
        String[] rest = Arrays.copyOfRange(args, walk.consumed(), args.length);
        if (node.handler() == null) {
            if (registration.legacy() != null) {
                if (node.playerOnly() && !(sender instanceof Player)) { core.send(sender, "command.error.player-only"); return; }
                registration.legacy().run(sender, label, canonical(sender, language, args));
                return;
            }
            if (!node.children().isEmpty()) {
                if (rest.length > 0) unknown(sender, label, language, node, rest[0]);
                else help(sender, label, node, 1);
            } else help(sender, label, root(), 1);
            return;
        }
        if (node.playerOnly() && !(sender instanceof Player)) { core.send(sender, "command.error.player-only"); return; }
        if (node.args().isEmpty() && rest.length > 0 && !node.children().isEmpty()) { unknown(sender, label, language, node, rest[0]); return; }
        CommandContext context;
        try { context = parse(sender, label, args, node, rest, language); }
        catch (CommandException failure) { report(sender, label, node, failure); return; }
        if (!registration.cooldowns().tryUse(sender, node)) {
            core.send(sender, "command.error.cooldown", "seconds", registration.cooldowns().remainingSeconds(sender, node));
            return;
        }
        CommandHandler handler = node.handler();
        Runnable run = () -> {
            try { handler.execute(context); }
            catch (CommandException failure) { report(sender, label, node, failure); }
            catch (RuntimeException | LinkageError failure) {
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("commands", registration.plugin().getName(), "/" + label + " " + String.join(" ", args), failure);
                core.send(sender, "command.error.internal");
            }
        };
        if (node.async()) {
            try { Bukkit.getScheduler().runTaskAsynchronously(registration.plugin(), run); }
            catch (RuntimeException | LinkageError noScheduler) { run.run(); }
        } else run.run();
    }

    /** Parses the arguments of {@code node} from {@code rest}. */
    CommandContext parse(CommandSender sender, String label, String[] all, CommandSpec node, String[] rest, String language) {
        CommandContext context = new CommandContext(registration, sender, label, all, node, new LinkedHashMap<>(), new LinkedHashMap<>());
        int index = 0;
        for (Arg<?> arg : node.args()) {
            if (index >= rest.length) {
                if (arg.isOptional()) break;
                throw CommandException.withUsage("command.error.missing-arg", "arg", names.argName(node, arg, language));
            }
            String input;
            if (arg.kind() == Arg.Kind.GREEDY) { input = String.join(" ", Arrays.copyOfRange(rest, index, rest.length)); index = rest.length; }
            else input = rest[index++];
            context = context.withValue(arg.id(), value(arg, context, input, language), input);
        }
        if (index < rest.length) throw CommandException.withUsage("command.error.too-many");
        return context;
    }

    Object value(Arg<?> arg, CommandContext context, String input, String language) {
        if (arg.kind() == Arg.Kind.CHOICE || arg.kind() == Arg.Kind.BOOLEAN) {
            String key = keyword(arg, input, language);
            if (key == null) {
                List<String> shown = new ArrayList<>();
                for (String k : arg.suggestedKeys()) shown.add(names.value(root(), k, language));
                throw new CommandException("command.error.bad-choice", "input", input, "choices", String.join(", ", shown));
            }
            return arg.fromKey(key);
        }
        return arg.parser().parse(context, input);
    }

    /** The English key a typed keyword stands for, {@code null} if none. */
    String keyword(Arg<?> arg, String input, String language) {
        String t = lower(input);
        for (String key : arg.keys()) if (names.valueNames(root(), key, language).contains(t)) return key;
        for (String key : arg.keys()) if (key.equals(t)) return key;
        for (String key : arg.keys()) if (names.acceptedValues(root(), key, language).contains(t)) return key;
        return null;
    }

    private void report(CommandSender sender, String label, CommandSpec node, CommandException failure) {
        Lang lang = names.lang().has(failure.key()) ? names.lang() : names.core();
        lang.send(sender, failure.key(), failure.placeholders());
        if (failure.showUsage()) names.core().send(sender, "command.error.usage", "usage", usage(sender, label, node));
    }

    private void unknown(CommandSender sender, String label, String language, CommandSpec node, String input) {
        Lang core = names.core();
        core.send(sender, "command.error.unknown-sub", "input", input);
        String best = closest(sender, language, node, input);
        String prefix = "/" + label + pathText(node, language);
        if (best != null) {
            Component line = core.component(sender, "command.error.did-you-mean", "suggestion", best)
                    .clickEvent(ClickEvent.suggestCommand(prefix + " " + best + " "))
                    .hoverEvent(HoverEvent.showText(Component.text(prefix + " " + best)));
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line);
        }
        if (root().helpEnabled()) {
            String help = "/" + label + " " + names.helpName(language);
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, core.component(sender, "command.error.see-help", "command", help)
                    .clickEvent(ClickEvent.runCommand(help)).hoverEvent(HoverEvent.showText(Component.text(help))));
        }
    }

    /** Did-you-mean: the visible child name (any accepted name) closest to the input, own language preferred. */
    String closest(CommandSender sender, String language, CommandSpec node, String input) {
        String t = lower(input);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (CommandSpec child : node.children().values()) {
            if (!visible(sender, child)) continue;
            List<String> own = names.own(child, language);
            for (String candidate : names.accepted(child, language)) {
                int d = distance(t, candidate);
                if (t.length() >= 2 && (candidate.startsWith(t) || t.startsWith(candidate))) d = Math.min(d, 1);
                int limit = Math.max(1, Math.min(3, candidate.length() / 3));
                boolean preferred = own.isEmpty() ? candidate.equals(child.name()) : own.contains(candidate);
                int score = d * 2 + (preferred ? 0 : 1);
                if (d <= limit && score < bestScore) { bestScore = score; best = candidate; }
            }
        }
        return best;
    }

    /** Edit distance with swapped neighbours counting as one edit (optimal string alignment). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        return d[a.length()][b.length()];
    }

    /** Arguments with matched subcommand tokens and keyword values replaced by their English ids (adapter). */
    String[] canonical(CommandSender sender, String language, String[] args) {
        String[] out = args.clone();
        Walk walk = walk(sender, language, args, args.length);
        if (walk.help()) return out;
        List<String> ids = walk.node().idPath();
        for (int i = 0; i < walk.consumed() && i < ids.size(); i++) out[i] = ids.get(i);
        int index = walk.consumed();
        for (Arg<?> arg : walk.node().args()) {
            if (index >= out.length || arg.kind() == Arg.Kind.GREEDY) break;
            if (arg.kind() == Arg.Kind.CHOICE || arg.kind() == Arg.Kind.BOOLEAN) {
                String key = keyword(arg, out[index], language);
                if (key != null) out[index] = key;
            }
            index++;
        }
        return out;
    }

    // ------------------------------------------------------------------ completion

    List<String> suggest(CommandSender sender, String label, String[] rawArgs) {
        String[] args = rawArgs == null || rawArgs.length == 0 ? new String[]{""} : rawArgs;
        if (!registration.plugin().isEnabled() || !allowed(sender, root())) return List.of();
        String language = language(sender);
        int last = args.length - 1;
        String prefix = args[last];
        Walk walk = walk(sender, language, args, last);
        Set<String> out = new LinkedHashSet<>();
        if (walk.help()) {
            if (last == 1) for (CommandSpec child : root().children().values()) if (visible(sender, child)) out.add(names.display(child, language));
            return filter(out, prefix);
        }
        CommandSpec node = walk.node();
        for (CommandSpec n = node; n != null; n = n.parent()) if (!allowed(sender, n)) return List.of();
        int position = last - walk.consumed();
        Set<String> extra = new LinkedHashSet<>();
        if (position == 0) {
            for (CommandSpec child : node.children().values()) {
                if (!visible(sender, child)) continue;
                String shown = names.display(child, language);
                if (starts(shown, prefix) || prefix.isEmpty()) out.add(shown);
                else for (String other : names.accepted(child, language)) if (starts(other, prefix)) { extra.add(other); break; }
            }
            if (node.isRoot() && root().helpEnabled() && !node.children().containsKey("help")) {
                String help = names.helpName(language);
                if (starts(help, prefix)) out.add(help);
                else if (starts("help", prefix)) extra.add("help");
            }
        }
        if (!node.playerOnly() || sender instanceof Player) {
            Arg<?> arg = argAt(node, position);
            if (arg != null) {
                CommandContext context = lenient(sender, label, args, node, Arrays.copyOfRange(args, walk.consumed(), last), language);
                suggestArg(arg, context, prefix, language, out, extra);
            }
        }
        if (registration.legacy() != null && registration.legacy().completer() != null) {
            String[] canonical = canonical(sender, language, args);
            Set<String> localizedIds = new LinkedHashSet<>();
            if (position == 0) for (CommandSpec child : node.children().values())
                if (!names.display(child, language).equalsIgnoreCase(child.name())) localizedIds.add(child.name());
            for (String s : registration.legacy().complete(sender, label, canonical))
                if (s != null && !localizedIds.contains(lower(s))) out.add(s);
        }
        List<String> result = filter(out, prefix);
        if (!extra.isEmpty()) {
            List<String> merged = new ArrayList<>(result);
            for (String e : extra) if (!merged.contains(e)) merged.add(e);
            return merged;
        }
        return result;
    }

    private static Arg<?> argAt(CommandSpec node, int position) {
        if (node.args().isEmpty()) return null;
        if (position < node.args().size()) return node.args().get(position);
        Arg<?> last = node.args().get(node.args().size() - 1);
        return last.kind() == Arg.Kind.GREEDY ? last : null; // a greedy argument completes every further word
    }

    private void suggestArg(Arg<?> arg, CommandContext context, String prefix, String language, Set<String> out, Set<String> extra) {
        if (arg.suggester() != null) {
            try {
                var values = arg.suggester().suggest(context, prefix);
                if (values != null) for (String v : values) if (v != null) out.add(v);
            } catch (RuntimeException | LinkageError failure) {
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("commands", registration.plugin().getName(), "suggest " + arg.id(), failure);
            }
            return;
        }
        switch (arg.kind()) {
            case CHOICE, BOOLEAN -> {
                for (String key : arg.suggestedKeys()) {
                    String shown = names.value(root(), key, language);
                    if (prefix.isEmpty() || starts(shown, prefix)) out.add(shown);
                    else for (String other : names.acceptedValues(root(), key, language)) if (starts(other, prefix)) { extra.add(other); break; }
                }
            }
            case PLAYER, PLAYER_NAME -> {
                try { for (Player p : Bukkit.getOnlinePlayers()) if (Arg.visible(context, p)) out.add(p.getName()); }
                catch (RuntimeException ignored) { }
            }
            case WORLD -> {
                try { for (World w : Bukkit.getWorlds()) out.add(w.getName()); }
                catch (RuntimeException ignored) { }
            }
            case INTEGER -> {
                if (arg.max() - arg.min() <= 10) for (long v = (long) arg.min(); v <= (long) arg.max(); v++) out.add(Long.toString(v));
            }
            default -> { }
        }
    }

    /** Context for completion: earlier arguments parsed where possible, errors ignored. */
    private CommandContext lenient(CommandSender sender, String label, String[] all, CommandSpec node, String[] before, String language) {
        CommandContext context = new CommandContext(registration, sender, label, all, node, new LinkedHashMap<>(), new LinkedHashMap<>());
        for (int i = 0; i < before.length && i < node.args().size(); i++) {
            Arg<?> arg = node.args().get(i);
            if (arg.kind() == Arg.Kind.GREEDY) break;
            try { context = context.withValue(arg.id(), value(arg, context, before[i], language), before[i]); }
            catch (RuntimeException ignored) { }
        }
        return context;
    }

    private static List<String> filter(Set<String> values, String prefix) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (prefix.isEmpty() || starts(v, prefix)) out.add(v);
        return out;
    }

    private static boolean starts(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------------------ usage and help

    /** {@code " ziel"} – localized subcommand names from the root to the node, each with a leading space. */
    String pathText(CommandSpec node, String language) {
        StringBuilder out = new StringBuilder();
        List<CommandSpec> chain = new ArrayList<>();
        for (CommandSpec n = node; n != null && !n.isRoot(); n = n.parent()) chain.add(0, n);
        for (CommandSpec n : chain) out.append(' ').append(names.display(n, language));
        return out.toString();
    }

    /** Arguments part of the usage: language file {@code <node>.usage}, the spec's fallback, or generated. */
    String argsText(CommandSpec node, String language) {
        String own = names.optionalPlain(language, node.langPath() + ".usage");
        if (own != null && !own.isBlank()) return own.trim();
        if (node.usage() != null) return node.usage();
        List<String> parts = new ArrayList<>();
        for (Arg<?> arg : node.args()) {
            String n = names.argName(node, arg, language);
            if (arg.kind() == Arg.Kind.GREEDY && !n.endsWith("...")) n = n + "...";
            parts.add(arg.isOptional() ? "[" + n + "]" : n);
        }
        return String.join(" ", parts);
    }

    String usage(CommandSender sender, String label, CommandSpec node) {
        String language = language(sender);
        String args = argsText(node, language);
        return "/" + label + pathText(node, language) + (args.isEmpty() ? "" : " " + args);
    }

    String description(CommandSender sender, CommandSpec node) {
        String text = names.optionalPlain(language(sender), node.langPath() + ".description");
        if (text != null) return text;
        return node.description() == null ? "" : node.description();
    }

    /** {@code /<label> help [page | sub... [page]]}. */
    void helpCommand(CommandSender sender, String label, String[] args) {
        String language = language(sender);
        CommandSpec node = root();
        int page = 1;
        for (String token : args) {
            if (token.matches("\\d{1,4}")) { page = Integer.parseInt(token); break; }
            CommandSpec child = match(node, token, language);
            if (child == null || !visible(sender, child)) { unknown(sender, label, language, node, token); return; }
            node = child;
        }
        help(sender, label, node, page);
    }

    /** Entries of the node's subtree that do something, visible to the sender, in declaration order. */
    List<CommandSpec> entries(CommandSender sender, CommandSpec node) {
        List<CommandSpec> out = new ArrayList<>();
        collect(sender, node, out, true);
        return out;
    }

    private void collect(CommandSender sender, CommandSpec node, List<CommandSpec> out, boolean first) {
        if (!first && !visible(sender, node)) return;
        if (runnable(node) && (!node.isRoot() || node.children().isEmpty() || !node.args().isEmpty() || node.handler() != null)) out.add(node);
        for (CommandSpec child : node.children().values()) collect(sender, child, out, false);
    }

    void help(CommandSender sender, String label, CommandSpec node, int requestedPage) {
        Lang core = names.core();
        String language = language(sender);
        List<CommandSpec> entries = entries(sender, node);
        int size = Settings.current().helpPageSize();
        int pages = Math.max(1, (entries.size() + size - 1) / size);
        int page = Math.max(1, Math.min(requestedPage, pages));
        String title = "/" + label + pathText(node, language);
        core.send(sender, "command.help.header", "command", title, "page", page, "pages", pages);
        String description = description(sender, node);
        if (page == 1 && !description.isBlank()) core.send(sender, "command.help.description", "description", description);
        if (entries.isEmpty()) core.send(sender, "command.help.empty");
        for (CommandSpec entry : entries.subList((page - 1) * size, Math.min(entries.size(), page * size)))
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line(sender, label, entry, language));
        if (pages > 1) {
            String base = "/" + label + " " + names.helpName(language) + pathText(node, language) + " ";
            Component nav = Component.empty();
            if (page > 1) nav = nav.append(core.component(sender, "command.help.previous").clickEvent(ClickEvent.runCommand(base + (page - 1))));
            if (page > 1 && page < pages) nav = nav.append(Component.text("  "));
            if (page < pages) nav = nav.append(core.component(sender, "command.help.next").clickEvent(ClickEvent.runCommand(base + (page + 1))));
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, nav);
        }
    }

    /** One clickable help line: runs commands without required arguments, otherwise types them into the chat. */
    Component line(CommandSender sender, String label, CommandSpec entry, String language) {
        Lang core = names.core();
        String usage = usage(sender, label, entry);
        String description = description(sender, entry);
        boolean needsInput = entry.args().stream().anyMatch(a -> !a.isOptional());
        String command = "/" + label + pathText(entry, language);
        Component hover = core.component(sender, needsInput ? "command.help.hover-suggest" : "command.help.hover-run", "command", usage, "description", description);
        Component line = core.component(sender, description.isBlank() ? "command.help.line-plain" : "command.help.line", "usage", usage, "description", description);
        return line.clickEvent(needsInput || !entry.args().isEmpty() ? ClickEvent.suggestCommand(command + " ") : ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(hover));
    }

    // ------------------------------------------------------------------ diagnostics

    /** Names that select two different children of one node in a language: "de: ziel → dest, target". */
    List<String> conflicts() {
        List<String> out = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>(names.lang().languages());
        codes.add("en");
        conflicts(root(), codes, out);
        return out;
    }

    private void conflicts(CommandSpec node, Set<String> codes, List<String> out) {
        if (node.children().isEmpty()) return;
        for (String code : codes) {
            Map<String, String> owner = new LinkedHashMap<>();
            for (CommandSpec child : node.children().values()) {
                List<String> all = new ArrayList<>(names.own(child, code));
                all.add(child.name());
                all.addAll(child.aliases());
                for (String n : all) {
                    String before = owner.putIfAbsent(n, child.name());
                    if (before != null && !before.equals(child.name()))
                        out.add(code + ": " + "/" + root().name() + pathText(node, "en") + " " + n + " → " + before + ", " + child.name());
                }
            }
            if (node.isRoot() && root().helpEnabled())
                for (String h : names.helpNames(code))
                    if (owner.containsKey(h) && !owner.get(h).equals("help")) out.add(code + ": /" + root().name() + " " + h + " → help, " + owner.get(h));
        }
        for (CommandSpec child : node.children().values()) conflicts(child, codes, out);
    }
}
