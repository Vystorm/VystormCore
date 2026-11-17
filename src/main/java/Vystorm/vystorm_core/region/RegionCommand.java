package Vystorm.vystorm_core.region;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /vregion at [Spieler]} und {@code /vregion list [Art]}: Einblick in das gemeinsame Regionsregister. */
public final class RegionCommand implements TabExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (!sender.hasPermission("vystorm_core.admin")) { lang.send(sender, "region.no-permission"); return true; }
        var service = RegionService.get();
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            var regions = args.length >= 2 ? service.all(args[1].toLowerCase(Locale.ROOT)) : service.all();
            lang.send(sender, "region.list", "count", regions.size());
            regions.stream().limit(40).forEach(r -> sender.sendMessage(line(r)));
            if (regions.size() > 40) lang.send(sender, "region.more", "count", regions.size() - 40);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("at")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
            if (target == null) { lang.send(sender, "region.player-not-found"); return true; }
            Location at = target.getLocation();
            var regions = service.at(at);
            lang.send(sender, "region.at", "player", target.getName(), "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ(),
                    "count", regions.isEmpty() ? lang.plain(sender, "region.none") : String.valueOf(regions.size()));
            regions.forEach(r -> sender.sendMessage(line(r)));
            return true;
        }
        lang.send(sender, "region.usage");
        return true;
    }

    private static String line(Region r) {
        var shape = r.shape();
        String kind = shape.getClass().getSimpleName();
        return "§7 · §f" + r.key() + " §8[§7P " + r.priority() + "§8, §7" + kind + "§8, §7" + r.owner() + "§8]"
                + (r.flags().isEmpty() ? "" : " §7Flags " + r.flags())
                + (r.attributes().isEmpty() ? "" : " §7" + r.attributes());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vystorm_core.admin") || args.length == 0) return List.of();
        List<String> out = List.of();
        if (args.length == 1) out = List.of("at", "list");
        else if (args.length == 2 && args[0].equalsIgnoreCase("at")) out = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        else if (args.length == 2 && args[0].equalsIgnoreCase("list")) out = RegionService.get().all().stream().map(Region::kind).distinct().toList();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
