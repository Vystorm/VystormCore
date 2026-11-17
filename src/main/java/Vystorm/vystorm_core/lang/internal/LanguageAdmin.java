package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /vadmin language [reload | audit [plugin|*] [lang] | export <plugin> <lang> [yml|csv] | import <file> [plugin]
 * [lang]]}. The caller checks {@code vystorm_core.admin}. Core-internal.
 */
public final class LanguageAdmin {
    public static final List<String> SUBCOMMANDS = List.of("reload", "audit", "export", "import");
    /** Keys listed per kind in a filtered audit before "… and N more". */
    static final int KEYS_SHOWN = 40;

    private LanguageAdmin() {}

    private static Lang lang() { return Localization.get().core(); }

    /** {@code plugins/vystorm_core/translations}. */
    public static Path directory(Plugin core) { return core.getDataFolder().toPath().resolve("translations"); }

    /** Handles {@code args} = {@code [language|lang, sub, ...]}. */
    public static void execute(Plugin core, CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                int count = CoreLanguage.reload();
                Vystorm.vystorm_core.commands.CoreCommands.refreshAliases(); // localized root aliases from the reloaded files
                lang().send(sender, "admin.language-reloaded", "count", count);
                overview(sender);
            }
            case "audit" -> audit(sender, args.length > 2 ? args[2] : null, args.length > 3 ? args[3] : null);
            case "export" -> export(core, sender, args);
            case "import" -> importFile(core, sender, args);
            case "" -> overview(sender);
            default -> lang().send(sender, "admin.language-usage");
        }
    }

    static void overview(CommandSender sender) {
        Localization service = Localization.get();
        StringBuilder bundles = new StringBuilder();
        for (Lang b : sorted()) { if (!bundles.isEmpty()) bundles.append(", "); bundles.append(b.namespace()).append(' ').append(b.languages()); }
        lang().send(sender, "admin.language", "default", service.defaultLanguage(), "bundles", bundles.toString());
        StringBuilder steps = new StringBuilder();
        new java.util.TreeMap<>(service.fallbacks()).forEach((from, to) -> { if (!steps.isEmpty()) steps.append(", "); steps.append(from).append(" → ").append(to); });
        lang().send(sender, "admin.language-setup", "server", service.serverLanguage(), "forced", service.forced(),
                "coverage", (int) service.minCoverage(), "fallbacks", steps.toString(), "languages", String.join(", ", service.selectableLanguages()));
        lang().send(sender, "admin.language-usage");
    }

    private static List<Lang> sorted() {
        return Localization.get().bundles().stream().sorted(Comparator.comparing(Lang::namespace)).toList();
    }

    // ------------------------------------------------------------------ audit

    static void audit(CommandSender sender, String pluginFilter, String languageFilter) {
        Localization service = Localization.get();
        String plugin = pluginFilter == null || pluginFilter.equals("*") || pluginFilter.equalsIgnoreCase("all") ? null : pluginFilter.toLowerCase(Locale.ROOT);
        String language = languageFilter == null ? null : Localization.normalize(languageFilter);
        // "/vadmin language audit zh_cn": a language instead of a plugin
        if (plugin != null && language == null && service.bundle(plugin).isEmpty() && service.languages().contains(Localization.normalize(plugin))) {
            language = Localization.normalize(plugin);
            plugin = null;
        }
        List<Lang> bundles = new ArrayList<>();
        for (Lang b : sorted()) if (plugin == null || b.namespace().equals(plugin)) bundles.add(b);
        if (bundles.isEmpty()) { unknownPlugin(sender, pluginFilter); return; }
        Set<String> languages = new TreeSet<>();
        if (language != null) languages.add(language); else bundles.forEach(b -> languages.addAll(b.languages()));
        languages.remove(Localization.FALLBACK);
        lang().send(sender, "admin.language-audit-header", "plugins", bundles.size(), "languages", languages.size());
        boolean details = plugin != null || language != null;
        for (String code : languages) {
            if (language == null && plugin == null)
                lang().send(sender, "admin.language-audit-total", "language", code, "name", CoreLanguage.nativeName(code),
                        "coverage", percent(service.coverage(code)));
            for (Lang bundle : bundles) {
                Lang.Audit audit = bundle.audit(code);
                List<String> missing = new ArrayList<>(bundle.keys());
                missing.retainAll(audit.missing());
                Component line = lang().component(sender, "admin.language-audit-line", "plugin", bundle.namespace(), "language", code,
                        "coverage", percent(bundle.coverage(code)), "total", bundle.keys().size(), "missing", missing.size(),
                        "untranslated", audit.untranslated().size(), "placeholders", audit.placeholderMismatch().size());
                String command = "/vadmin language export " + bundle.namespace() + " " + code;
                Component button = lang().component(sender, "admin.language-export-button")
                        .clickEvent(ClickEvent.runCommand(command)).hoverEvent(lang().component(sender, "admin.language-export-hover", "command", command));
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line.append(Component.space()).append(button));
                if (!details) continue;
                keys(sender, "missing", missing);
                keys(sender, "untranslated", audit.untranslated());
                keys(sender, "placeholders", audit.placeholderMismatch());
                keys(sender, "unfilled-tags", audit.unfilledTags());
            }
        }
        if (!details) lang().send(sender, "admin.language-audit-hint");
    }

    private static void keys(CommandSender sender, String kind, List<String> keys) {
        if (keys.isEmpty()) return;
        List<String> shown = keys.size() > KEYS_SHOWN ? keys.subList(0, KEYS_SHOWN) : keys;
        lang().send(sender, "admin.language-audit-keys", "kind", lang().plain(sender, "admin.language-kind-" + kind), "keys", String.join(", ", shown));
        if (keys.size() > shown.size()) lang().send(sender, "admin.language-audit-more", "count", keys.size() - shown.size());
    }

    static String percent(double value) {
        double floor = Math.floor(value * 10 + 1e-9) / 10;
        return floor == Math.rint(floor) ? Integer.toString((int) floor) : String.format(Locale.ROOT, "%.1f", floor);
    }

    private static void unknownPlugin(CommandSender sender, String plugin) {
        lang().send(sender, "admin.language-unknown-plugin", "plugin", String.valueOf(plugin),
                "plugins", String.join(", ", sorted().stream().map(Lang::namespace).toList()));
    }

    // ------------------------------------------------------------------ export / import

    static void export(Plugin core, CommandSender sender, String[] args) {
        if (args.length < 4) { lang().send(sender, "admin.language-usage"); return; }
        Optional<Lang> bundle = Localization.get().bundle(args[2]);
        if (bundle.isEmpty()) { unknownPlugin(sender, args[2]); return; }
        String code = Localization.normalize(args[3]);
        if (!code.matches("[a-z]{2,8}(_[a-z0-9]{1,8})*") || code.equals(Localization.FALLBACK)) {
            lang().send(sender, "admin.language-error-language", "detail", args[3]);
            return;
        }
        TranslationFiles.Format format = args.length > 4 ? TranslationFiles.Format.parse(args[4]).orElse(null) : TranslationFiles.Format.YAML;
        if (format == null) { lang().send(sender, "admin.language-usage"); return; }
        try {
            TranslationFiles.Export result = TranslationFiles.export(bundle.get(), code, format, directory(core));
            String relative = "plugins/" + core.getDataFolder().getName() + "/translations/" + result.file().getFileName();
            lang().send(sender, "admin.language-exported", "plugin", bundle.get().namespace(), "language", code,
                    "count", result.count(), "missing", result.missing(), "file", relative);
            String command = "/vadmin language import " + result.file().getFileName();
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, lang().component(sender, "admin.language-import-button")
                    .clickEvent(ClickEvent.suggestCommand(command)).hoverEvent(lang().component(sender, "admin.language-import-hover", "command", command)));
        } catch (IOException | RuntimeException failure) {
            lang().send(sender, "admin.language-error-io", "detail", String.valueOf(failure.getMessage()));
        }
    }

    static void importFile(Plugin core, CommandSender sender, String[] args) {
        Path dir = directory(core);
        if (args.length < 3) {
            lang().send(sender, "admin.language-usage");
            lang().send(sender, "admin.language-files", "files", String.join(", ", TranslationFiles.files(dir)));
            return;
        }
        try {
            Path file = TranslationFiles.resolve(dir, args[2]);
            TranslationFiles.Import result = TranslationFiles.importFile(file, ns -> Localization.get().bundle(ns),
                    args.length > 3 ? args[3] : null, args.length > 4 ? args[4] : null);
            lang().send(sender, "admin.language-imported", "file", args[2], "plugin", result.namespace(), "language", result.language(),
                    "added", result.added(), "changed", result.changed(), "unchanged", result.unchanged(), "skipped", result.skipped().size(),
                    "target", result.target().toString(), "backup", result.backup() == null ? "-" : result.backup().getFileName().toString());
            int shown = 0;
            for (Map.Entry<String, String> skip : result.skipped().entrySet()) {
                if (shown++ >= KEYS_SHOWN) { lang().send(sender, "admin.language-audit-more", "count", result.skipped().size() - KEYS_SHOWN); break; }
                lang().send(sender, "admin.language-import-skipped", "key", skip.getKey(), "reason", lang().plain(sender, "admin.language-reason-" + skip.getValue()));
            }
            if (result.added() + result.changed() > 0) CoreLanguage.refreshSettings();
        } catch (TranslationFiles.TranslationException failure) {
            lang().send(sender, "admin.language-error-" + failure.reason, "detail", failure.detail);
            if (failure.reason.equals("file-missing")) lang().send(sender, "admin.language-files", "files", String.join(", ", TranslationFiles.files(dir)));
        } catch (IOException | RuntimeException failure) {
            lang().send(sender, "admin.language-error-io", "detail", String.valueOf(failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------ tab completion

    /** Completion for {@code /vadmin language …} ({@code args[0]} is "language"/"lang"). */
    public static List<String> complete(Plugin core, String[] args) {
        List<String> out = new ArrayList<>();
        Localization service = Localization.get();
        if (args.length == 2) out.addAll(SUBCOMMANDS);
        else if (args.length >= 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "audit" -> {
                    if (args.length == 3) { out.add("*"); sorted().forEach(b -> out.add(b.namespace())); out.addAll(service.languages()); }
                    else if (args.length == 4) out.addAll(service.languages());
                }
                case "export" -> {
                    if (args.length == 3) sorted().forEach(b -> out.add(b.namespace()));
                    else if (args.length == 4) { out.addAll(service.languages()); out.remove(Localization.FALLBACK); out.add("zh_cn"); }
                    else if (args.length == 5) out.addAll(List.of("yml", "csv"));
                }
                case "import" -> {
                    if (args.length == 3) out.addAll(TranslationFiles.files(directory(core)));
                    else if (args.length == 4) sorted().forEach(b -> out.add(b.namespace()));
                    else if (args.length == 5) out.addAll(service.languages());
                }
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().toList();
    }
}
