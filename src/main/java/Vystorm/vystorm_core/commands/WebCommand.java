package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.web.WebServices;
import Vystorm.vystorm_core.web.internal.WebPlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /web [modul] | logout | status | link <spieler|uuid> [modul]}.
 * <ul>
 *   <li>{@code /web [modul]}: wie {@link WebServices#openOrLink}: direkt im Overlay/Browser der Client-Mod, wenn der
 *       Spieler sie hat und dem Server vertraut, sonst Einmal-Login-Link in den Chat (seit 0.25.3; vorher immer Link).</li>
 *   <li>{@code /web logout}: beendet alle eigenen Web-Sitzungen.</li>
 *   <li>{@code /web status}: Zustand der Plattform (nur {@code vystorm_core.admin}).</li>
 *   <li>{@code /web link <spieler|uuid> [modul]}: nur Konsole/RCON, gibt einen Login-Link für einen Spieler aus
 *       (Support und Tests, wird im Audit-Log vermerkt).</li>
 * </ul>
 */
public final class WebCommand implements TabExecutor {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        switch (sub) {
            case "status" -> {
                if (!sender.hasPermission("vystorm_core.admin")) { lang.send(sender, "web.no-permission"); return true; }
                lang.send(sender, "web.status", "status", WebPlatform.status());
                lang.send(sender, "web.modules", "modules", String.join(", ", WebServices.modules()));
                return true;
            }
            case "link" -> {
                if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
                    lang.send(sender, "web.console-only");
                    return true;
                }
                if (args.length < 2) { lang.send(sender, "web.link-usage"); return true; }
                OfflinePlayer target = resolve(args[1]);
                if (target == null) { lang.send(sender, "web.unknown-player"); return true; }
                String module = args.length > 2 ? args[2] : null;
                if (module != null && !WebServices.registered(module)) { lang.send(sender, "web.unknown-module", "module", module); return true; }
                String name = target.getName() == null ? args[1] : target.getName();
                var link = WebPlatform.issueLogin(target.getUniqueId(), name, module, "console").map(Vystorm.vystorm_core.web.WebLogin::url);
                if (link.isPresent()) lang.send(sender, "web.link", "player", name, "link", link.get());
                else lang.send(sender, "web.disabled");
                return true;
            }
            case "logout" -> {
                if (!(sender instanceof Player player)) { lang.send(sender, "web.players-only"); return true; }
                int n = WebPlatform.revoke(player.getUniqueId(), player.getName(), "logout command");
                if (n == 0) lang.send(player, "web.logout-none");
                else lang.send(player, "web.logout-done", "count", n);
                return true;
            }
            default -> {
                if (!(sender instanceof Player player)) { lang.send(sender, "web.console-usage"); return true; }
                String module = sub.isEmpty() ? null : sub;
                if (module != null && !WebServices.registered(module)) {
                    lang.send(player, "web.unknown-ui", "name", args[0]);
                    return true;
                }
                WebServices.openOrLink(player, module == null ? "" : module, "", null);
                return true;
            }
        }
    }

    private static OfflinePlayer resolve(String arg) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(arg));
        } catch (IllegalArgumentException notUuid) {
            Player online = Bukkit.getPlayerExact(arg);
            return online != null ? online : Vystorm.vystorm_core.compat.PlatformCompat.getOfflinePlayerIfCached(arg);
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        boolean console = sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender instanceof Player) out.addAll(modules(sender));
            if (sender instanceof Player) out.add("logout");
            if (sender.hasPermission("vystorm_core.admin")) out.add("status");
            if (console) out.add("link");
        } else if (console && args[0].equalsIgnoreCase("link")) {
            if (args.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            else if (args.length == 3) out.addAll(WebServices.modules());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static List<String> modules(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (String id : WebServices.modules()) {
            var module = WebPlatform.modules().get(id);
            if (module.isEmpty() || module.get().module().permission().map(node -> !sender.hasPermission(node)).orElse(false)) continue;
            out.add(id);
        }
        return out;
    }
}
