package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Localized names of command nodes and keyword values, read from the language bundles. Lookups are cheap (Lang caches
 * its tables), so nothing is cached here and edited language files apply after {@code /vadmin language reload}.
 */
final class Names {
    private final Lang lang;
    private final Lang core;

    Names(Lang lang, Lang core) {
        this.lang = lang;
        this.core = core;
    }

    Lang lang() { return lang; }

    Lang core() { return core; }

    /** The node's name shown to a viewer in {@code language} (root: its label). */
    String display(CommandSpec node, String language) {
        if (node.isRoot()) return node.name();
        String key = node.langPath() + ".name";
        if (!lang.has(key)) return node.name();
        List<String> names = words(raw(lang, language, key));
        return names.isEmpty() ? node.name() : names.get(0);
    }

    /**
     * Every name that selects the node, lower case: own language first (name, aliases), then English id and fixed
     * aliases, then the other installed languages.
     */
    List<String> accepted(CommandSpec node, String language) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(own(node, language));
        out.add(node.name());
        out.addAll(node.aliases());
        for (String code : lang.languages()) out.addAll(own(node, code));
        return List.copyOf(out);
    }

    /** Names a language file gives the node ({@code .name} and {@code .aliases}), English fallback included. */
    List<String> own(CommandSpec node, String language) {
        Set<String> out = new LinkedHashSet<>();
        if (!node.isRoot()) {
            String key = node.langPath() + ".name";
            if (lang.has(key)) out.addAll(lower(words(raw(lang, language, key))));
        }
        String aliases = node.langPath() + ".aliases";
        if (lang.has(aliases)) out.addAll(lower(words(raw(lang, language, aliases))));
        return List.copyOf(out);
    }

    /**
     * Localized root aliases per language, without English fallback for other languages (a language without its own
     * {@code .aliases} entry adds nothing). Map: alias → language code (first language that names it).
     */
    Map<String, String> rootAliases(CommandSpec root) {
        Map<String, String> out = new LinkedHashMap<>();
        String key = root.langPath() + ".aliases";
        if (!lang.has(key)) return out;
        String english = lang.raw(Localization.FALLBACK, key);
        List<String> codes = new ArrayList<>(lang.languages());
        codes.remove(Localization.FALLBACK);
        codes.add(0, Localization.FALLBACK);
        for (String code : codes) {
            String text = lang.raw(code, key);
            if (!code.equals(Localization.FALLBACK) && text.equals(english)) continue; // fallback to the English entry
            if (text.equals(key)) continue;
            for (String alias : lower(words(text))) {
                if (alias.equals(root.name()) || root.aliases().contains(alias)) continue;
                out.putIfAbsent(alias, code);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ keyword values

    /** Display name of a keyword value: plugin {@code <root>.values.<key>}, Core {@code command.values.<key>}, the key. */
    String value(CommandSpec root, String key, String language) {
        List<String> names = valueNames(root, key, language);
        return names.isEmpty() ? key : names.get(0);
    }

    /** Names of a keyword value in one language (fallback to English), lower case. */
    List<String> valueNames(CommandSpec root, String key, String language) {
        String own = root.langPath() + ".values." + key;
        if (lang.has(own)) return lower(words(raw(lang, language, own)));
        String shared = "command.values." + key;
        if (core.has(shared)) return lower(words(raw(core, language, shared)));
        return List.of();
    }

    /** Every accepted name of a value: own language, English key, all languages of the plugin and Core. */
    List<String> acceptedValues(CommandSpec root, String key, String language) {
        Set<String> out = new LinkedHashSet<>(valueNames(root, key, language));
        out.add(key);
        Set<String> codes = new LinkedHashSet<>(lang.languages());
        codes.addAll(core.languages());
        for (String code : codes) out.addAll(valueNames(root, key, code));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ help subcommand

    /** Name of the generated help subcommand in a language. */
    String helpName(String language) {
        List<String> names = core.has("command.help.name") ? words(raw(core, language, "command.help.name")) : List.of();
        return names.isEmpty() ? "help" : names.get(0);
    }

    /** All accepted names of the help subcommand. */
    List<String> helpNames(String language) {
        Set<String> out = new LinkedHashSet<>();
        out.add(helpName(language).toLowerCase(Locale.ROOT));
        out.add("help");
        out.add("?");
        Set<String> codes = new LinkedHashSet<>(core.languages());
        codes.addAll(lang.languages());
        for (String code : codes) {
            if (core.has("command.help.name")) out.addAll(lower(words(raw(core, code, "command.help.name"))));
            if (core.has("command.help.aliases")) out.addAll(lower(words(raw(core, code, "command.help.aliases"))));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ texts

    /** Text of a plugin key for the viewer if any file has it, else {@code null}. */
    String optionalPlain(String language, String key) {
        return lang.has(key) ? lang.plain(language, key) : null;
    }

    /** Argument display name. */
    String argName(CommandSpec node, Arg<?> arg, String language) {
        String key = node.langPath() + ".args." + arg.id();
        String text = lang.has(key) ? lang.plain(language, key).trim() : "";
        if (!text.isEmpty()) return text;
        if (!arg.keys().isEmpty() && arg.kind() != Arg.Kind.BOOLEAN && arg.keys().size() <= 6) {
            List<String> shown = new ArrayList<>();
            for (String k : arg.keys()) shown.add(value(node.root(), k, language));
            return String.join("|", shown);
        }
        if (arg.kind() == Arg.Kind.BOOLEAN) return value(node.root(), "on", language) + "|" + value(node.root(), "off", language);
        return arg.id().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ helpers

    /** {@code l.raw(code, key)}, or {@code null} when no file has the key (raw would return the key itself). */
    static String raw(Lang l, String code, String key) {
        String text = l.raw(code, key);
        return text == null || text.equals(key) ? null : text;
    }

    /** Splits a name entry (a list joined with line breaks, or comma separated) into words without blanks. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[\\n,]")) {
            String w = part.trim();
            if (w.startsWith("[") && w.endsWith("]")) w = w.substring(1, w.length() - 1).trim();
            if (!w.isEmpty() && w.chars().noneMatch(Character::isWhitespace) && !w.startsWith("/")) out.add(w);
        }
        return out;
    }

    static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }
}
