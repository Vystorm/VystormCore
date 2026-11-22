package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderBridge;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vadmin placeholders}: the namespaces with the plugin that owns them and the bridges to other placeholder
 * plugins; {@code /vadmin placeholders <namespace> [page]}: the values of a namespace with their descriptions;
 * {@code /vadmin placeholders test <text>}: the text resolved for the sender.
 *
 * <p>Core internal (called by the admin dashboard; permission checked there). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PlaceholdersAdmin {
    static final int PER_PAGE = 12;

    private PlaceholdersAdmin() {}

    /** {@code args} as typed after {@code /vadmin}: {@code placeholders ...}. */
    public static void handle(CommandSender sender, PlaceholderInterface placeholders, String[] args) {
        Lang lang = Localization.get().core();
        if (args.length >= 2 && args[1].equalsIgnoreCase("test")) {
            test(sender, placeholders, lang, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("rankings")) {
            if (args.length > 3) { lang.send(sender, "placeholders.rankings-usage"); return; }
            rankings(sender, placeholders, lang, args.length == 3 ? args[2] : null);
            return;
        }
        if (args.length == 1) {
            overview(sender, placeholders, lang);
            return;
        }
        int page = 1;
        if (args.length >= 3) {
            if (args.length > 3 || !args[2].matches("\\d{1,4}")) { lang.send(sender, "placeholders.usage"); return; }
            page = Integer.parseInt(args[2]);
        }
        Optional<PlaceholderNamespace> namespace = placeholders.namespace(args[1]);
        if (namespace.isEmpty()) {
            lang.send(sender, "placeholders.namespace-unknown", "namespace", args[1], "namespaces", String.join(", ", names(placeholders)));
            return;
        }
        values(sender, namespace.get(), lang, page);
    }

    private static List<String> names(PlaceholderInterface placeholders) {
        return placeholders.namespaces().stream().map(PlaceholderNamespace::name).toList();
    }

    private static void overview(CommandSender sender, PlaceholderInterface placeholders, Lang lang) {
        placeholders.refreshBridges();
        List<PlaceholderNamespace> namespaces = placeholders.namespaces();
        int values = 0;
        for (PlaceholderNamespace namespace : namespaces) values += namespace.values().size();
        lang.send(sender, "placeholders.header", "namespaces", namespaces.size(), "values", values);
        lang.send(sender, "placeholders.syntax");
        if (namespaces.isEmpty()) lang.send(sender, "placeholders.none");
        for (PlaceholderNamespace namespace : namespaces) {
            Component line = lang.component(sender, "placeholders.namespace-line", "namespace", namespace.name(),
                            "plugin", namespace.owner().getName(), "count", namespace.values().size(),
                            "dynamic", namespace.hasFallback() ? lang.component(sender, "placeholders.namespace-dynamic") : "")
                    .clickEvent(ClickEvent.runCommand("/vadmin placeholders " + namespace.name()))
                    .hoverEvent(HoverEvent.showText(lang.component(sender, "placeholders.namespace-hover", "namespace", namespace.name())));
            PlatformCompat.sendMessage(sender, line);
        }
        List<PlaceholderBridge> bridges = placeholders.bridges();
        if (!bridges.isEmpty()) lang.send(sender, "placeholders.bridges");
        for (PlaceholderBridge bridge : bridges) {
            String state = !bridge.available() ? "placeholders.bridge-missing"
                    : bridge.providing() ? "placeholders.bridge-connected" : "placeholders.bridge-consume-only";
            lang.send(sender, "placeholders.bridge-line", "bridge", bridge.name(), "state", lang.component(sender, state));
        }
        int rankings = placeholders.rankings().size();
        if (rankings > 0)
            PlatformCompat.sendMessage(sender, lang.component(sender, "placeholders.rankings-hint", "count", rankings)
                    .clickEvent(ClickEvent.runCommand("/vadmin placeholders rankings")));
        lang.send(sender, "placeholders.usage");
    }

    /** Rows shown per ranking in {@code /vadmin placeholders rankings}. */
    static final int TOP_SHOWN = 3;

    /** {@code /vadmin placeholders rankings [namespace]}: every declared ranking with its best three rows. */
    private static void rankings(CommandSender sender, PlaceholderInterface placeholders, Lang lang, String namespace) {
        List<PlaceholderRanking> all = placeholders.rankings();
        List<PlaceholderRanking> shown = namespace == null ? all
                : all.stream().filter(ranking -> ranking.namespace().equalsIgnoreCase(namespace)).toList();
        if (namespace != null && shown.isEmpty()) {
            lang.send(sender, "placeholders.rankings-unknown", "namespace", namespace, "namespaces",
                    String.join(", ", all.stream().map(PlaceholderRanking::namespace).distinct().toList()));
            return;
        }
        long namespaces = shown.stream().map(PlaceholderRanking::namespace).distinct().count();
        lang.send(sender, "placeholders.rankings-header", "count", shown.size(), "namespaces", namespaces);
        if (shown.isEmpty()) {
            lang.send(sender, "placeholders.rankings-none");
            return;
        }
        String language = lang.language(sender);
        for (PlaceholderRanking ranking : shown) {
            List<RankingSource.Row> rows = placeholders.top(ranking.id(), PlaceholderInterface.RANKING_MAX_ROWS);
            String entries = rows.size() >= PlaceholderInterface.RANKING_MAX_ROWS ? rows.size() + "+" : Integer.toString(rows.size());
            RankingInfo info = ranking.info();
            String description = info.description().isEmpty() ? "" : Localization.get().resolve(sender, info.description());
            Component line = lang.component(sender, "placeholders.rankings-line", "id", ranking.id(),
                            "label", Localization.get().resolve(sender, info.label()), "entries", entries, "plugin", ranking.owner().getName(),
                            "order", lang.component(sender, info.order() == RankingInfo.Order.ASCENDING
                                    ? "placeholders.rankings-ascending" : "placeholders.rankings-descending"))
                    .hoverEvent(HoverEvent.showText(lang.component(sender, "placeholders.rankings-hover",
                            "top", ranking.topPlaceholder(1, "name"), "value", ranking.topPlaceholder(1, "value"),
                            "rank", ranking.rankPlaceholder(), "mine", ranking.valuePlaceholder())));
            PlatformCompat.sendMessage(sender, line);
            if (!description.isEmpty()) lang.send(sender, "placeholders.rankings-description", "description", description);
            if (rows.isEmpty()) {
                lang.send(sender, "placeholders.rankings-empty");
                continue;
            }
            String unit = info.unit().isEmpty() ? "" : " " + Localization.get().resolve(sender, info.unit());
            for (int i = 0; i < Math.min(TOP_SHOWN, rows.size()); i++) {
                RankingSource.Row row = rows.get(i);
                lang.send(sender, "placeholders.rankings-row", "rank", i + 1, "name", row.name(),
                        "value", info.format().format(row.value(), language) + unit);
            }
        }
        lang.send(sender, "placeholders.rankings-syntax");
    }

    private static void values(CommandSender sender, PlaceholderNamespace namespace, Lang lang, int requested) {
        List<PlaceholderValue> values = namespace.values();
        int pages = Math.max(1, (values.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(1, Math.min(requested, pages));
        lang.send(sender, "placeholders.namespace-header", "namespace", namespace.name(), "plugin", namespace.owner().getName(),
                "count", values.size(), "page", page, "pages", pages);
        for (PlaceholderValue value : values.subList((page - 1) * PER_PAGE, Math.min(values.size(), page * PER_PAGE))) {
            String percent = percent(namespace.name(), value);
            String description = value.description().isEmpty()
                    ? lang.plain(sender, "placeholders.value-undescribed")
                    : Localization.get().resolve(sender, value.description());
            Component line = lang.component(sender, "placeholders.value-line", "placeholder", percent,
                    "short", brace(namespace.name(), value), "description", description,
                    "note", value.isAsyncSafe() ? "" : lang.component(sender, "placeholders.value-server-thread"));
            if (value.arguments().isEmpty())
                line = line.clickEvent(ClickEvent.suggestCommand("/vadmin placeholders test " + percent))
                        .hoverEvent(HoverEvent.showText(lang.component(sender, "placeholders.value-hover")));
            PlatformCompat.sendMessage(sender, line);
        }
        if (namespace.hasFallback()) lang.send(sender, "placeholders.namespace-dynamic-line", "namespace", namespace.name());
        if (page < pages) lang.send(sender, "placeholders.next-page", "command", "/vadmin placeholders " + namespace.name() + " " + (page + 1));
    }

    /** {@code %vystorm_core_time_[pattern]%}: the percent form with the argument hint. */
    static String percent(String namespace, PlaceholderValue value) {
        String arguments = value.arguments().isEmpty() ? "" : "_" + value.arguments().replace(' ', '_');
        return "%" + PlaceholderInterface.IDENTIFIER + "_" + namespace + "_" + value.key() + arguments + "%";
    }

    /** <code>{core:time:[pattern]}</code>: the brace form with the argument hint. */
    static String brace(String namespace, PlaceholderValue value) {
        String arguments = value.arguments().isEmpty() ? "" : ":" + value.arguments().replace(' ', ':');
        return "{" + namespace + ":" + value.key() + arguments + "}";
    }

    private static void test(CommandSender sender, PlaceholderInterface placeholders, Lang lang, String[] args) {
        if (args.length < 3) {
            lang.send(sender, "placeholders.test-usage");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String result = placeholders.resolveFor(sender, text);
        lang.send(sender, text.equals(result) ? "placeholders.test-unchanged" : "placeholders.test-result", "text", result);
        if (!(sender instanceof Player)) lang.send(sender, "placeholders.test-console");
    }

    /** Tab completion after {@code /vadmin placeholders}. */
    public static List<String> complete(PlaceholderInterface placeholders, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.add("test");
            if (!placeholders.rankings().isEmpty()) out.add("rankings");
            out.addAll(names(placeholders));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("rankings")) {
            placeholders.rankings().stream().map(PlaceholderRanking::namespace).distinct().forEach(out::add);
        } else if (args.length >= 3 && args[1].equalsIgnoreCase("test")) {
            // the namespaces as ready-made placeholder beginnings
            String typed = args[args.length - 1];
            if (typed.isEmpty() || typed.startsWith("%"))
                for (PlaceholderNamespace namespace : placeholders.namespaces())
                    for (PlaceholderValue value : namespace.values())
                        if (value.arguments().isEmpty()) out.add(percent(namespace.name(), value));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
