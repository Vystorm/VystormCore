package Vystorm.vystorm_core.lang.internal;

import Vystorm.vystorm_core.lang.CoreLanguage;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.lang.PlayerLanguageChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /language [code|auto|list]}, {@code /language <player> [code|auto]} (aliases {@code /lang}, {@code /sprache},
 * {@code /语言}). Without arguments players get the selection menu, the console the list. Core-internal.
 */
public final class LanguageCommand implements TabExecutor {
    /** Use {@code /language} (default: everyone). */
    public static final String PERMISSION = "vystorm_core.language";
    /** See and set other players' language (default: op, part of {@code vystorm_core.admin}). */
    public static final String PERMISSION_OTHERS = "vystorm_core.language.others";

    /** Binds the executor to Core's {@code language} command from plugin.yml. */
    public static void register(Plugin core) {
        if (!(core instanceof JavaPlugin plugin)) return;
        PluginCommand command = plugin.getCommand("language");
        if (command == null) return;
        LanguageCommand executor = new LanguageCommand();
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private static Lang lang() { return Localization.get().core(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Localization service = Localization.get();
        LanguageChoices choices = LanguageChoices.of(service);
        if (args.length == 0) {
            if (!service.forced() && sender instanceof Player player && LanguagePanel.open(player)) return true;
            list(sender, choices);
            return true;
        }
        if (args.length == 1) {
            String arg = args[0];
            if (arg.equalsIgnoreCase("list")) { list(sender, choices); return true; }
            Optional<String> code = choices.parse(arg);
            if (code.isPresent()) {
                if (sender instanceof Player player) apply(sender, player, code.get(), PlayerLanguageChangeEvent.Cause.COMMAND);
                else lang().send(sender, "language-select.console-usage");
                return true;
            }
            if (sender.hasPermission(PERMISSION_OTHERS)) {
                Player target = Bukkit.getPlayerExact(arg);
                if (target != null) { status(sender, target); return true; }
            }
            unknown(sender, arg, choices);
            return true;
        }
        if (!sender.hasPermission(PERMISSION_OTHERS)) { lang().send(sender, "language-select.others-no-permission"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { lang().send(sender, "language-select.player-offline", "player", args[0]); return true; }
        Optional<String> code = choices.parse(args[1]);
        if (code.isEmpty()) { unknown(sender, args[1], choices); return true; }
        apply(sender, target, code.get(), PlayerLanguageChangeEvent.Cause.ADMIN);
        return true;
    }

    /**
     * Stores {@code code} ({@code auto} or a code) for {@code target} and tells both sides. Players cannot choose while
     * {@code language.force} is on; admins can (the choice applies once force is off).
     */
    static boolean apply(CommandSender sender, Player target, String code, PlayerLanguageChangeEvent.Cause cause) {
        Localization service = Localization.get();
        if (service.forced() && cause != PlayerLanguageChangeEvent.Cause.ADMIN) {
            lang().send(sender, "language-select.forced", "language", CoreLanguage.nativeName(service.serverLanguage()));
            return false;
        }
        if (!CoreLanguage.setChoice(target, code, cause)) { lang().send(sender, "language-select.failed"); return false; }
        String now = service.language(target);
        String name = CoreLanguage.nativeName(now);
        if (code.equals(Localization.AUTO)) lang().send(target, "language-select.changed-auto", "language", name);
        else lang().send(target, "language-select.changed", "language", name);
        if (sender != target) {
            lang().send(sender, "language-select.admin-set", "player", target.getName(), "language",
                    code.equals(Localization.AUTO) ? Localization.AUTO + " → " + now : code);
            if (service.forced()) lang().send(sender, "language-select.forced", "language", CoreLanguage.nativeName(service.serverLanguage()));
        }
        return true;
    }

    private static void status(CommandSender sender, Player target) {
        Localization service = Localization.get();
        String client = Localization.clientLocale(target);
        lang().send(sender, "language-select.status", "player", target.getName(), "language", service.language(target),
                "choice", CoreLanguage.choice(target), "client", client.isEmpty() ? "?" : client);
    }

    private static void list(CommandSender sender, LanguageChoices choices) {
        Localization service = Localization.get();
        String current = sender instanceof Player p ? CoreLanguage.choice(p) : "";
        lang().send(sender, "language-select.list-header");
        for (LanguageChoices.Option option : choices.options())
            lang().send(sender, "language-select.list-line", "name", option.name(), "code", option.code(), "coverage", option.percent(),
                    "marker", option.code().equals(current) ? lang().component(sender, "language-select.current-marker") : "");
        if (service.forced()) lang().send(sender, "language-select.forced", "language", CoreLanguage.nativeName(service.serverLanguage()));
    }

    private static void unknown(CommandSender sender, String input, LanguageChoices choices) {
        List<String> codes = new ArrayList<>();
        codes.add(Localization.AUTO);
        choices.options().forEach(o -> codes.add(o.code()));
        lang().send(sender, "language-select.unknown", "input", input, "languages", String.join(", ", codes));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        LanguageChoices choices = LanguageChoices.of(Localization.get());
        boolean others = sender.hasPermission(PERMISSION_OTHERS);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(choices.complete(args[0]));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (others) for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        } else if (args.length == 2 && others && Bukkit.getPlayerExact(args[0]) != null) {
            out.addAll(choices.complete(args[1]));
        }
        return out.stream().distinct().toList();
    }
}
