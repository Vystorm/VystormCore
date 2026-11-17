package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.item.ItemRestrictions;
import Vystorm.vystorm_core.item.ItemServices;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.worlds.Visit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /vworlds list | visits | restore <Spieler> | check <Spieler> | legacy <Spieler|UUID> archive|retry} (Recht {@code vystorm_core.admin}). */
final class WorldsCommand implements TabExecutor {
    private final ManagedWorldRegistry registry;
    private final VisitEngine visits;
    private final VisitEnv env;

    WorldsCommand(ManagedWorldRegistry registry, VisitEngine visits, VisitEnv env) {
        this.registry = registry;this.visits = visits;this.env = env;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var lang = Localization.get().core();
        if (!sender.hasPermission("vystorm_core.admin")) { lang.send(sender, "worlds.admin.no-permission");return true; }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                lang.send(sender, "worlds.admin.list", "count", registry.all().size());
                for (ManagedWorldImpl world : registry.all())
                    lang.send(sender, "worlds.admin.entry", "id", world.id(), "world", world.spec().worldName(), "owner", world.owner().getName(),
                            "hash", ManagedWorldRegistry.abbreviate(world.spec().layoutHash()), "visitors", visits.visits(world.id()).size());
            }
            case "visits" -> {
                int count = 0;
                for (ManagedWorldImpl world : registry.all())
                    for (Visit visit : visits.visits(world.id())) {
                        Player p = Bukkit.getPlayer(visit.player());
                        lang.send(sender, "worlds.admin.visit", "player", p == null ? visit.player().toString() : p.getName(), "id", world.id(),
                                "phase", visit.phase().name(), "seconds", (System.currentTimeMillis() - visit.startedAt()) / 1000);
                        count++;
                    }
                if (count == 0) lang.send(sender, "worlds.admin.no-visits");
            }
            case "restore" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) { lang.send(sender, "worlds.admin.player-offline");return true; }
                lang.send(sender, "worlds.admin.restore." + visits.restoreOutcome(target).code(), "player", target.getName());
            }
            case "check" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) { lang.send(sender, "worlds.admin.player-offline");return true; }
                ItemStack hand = target.getInventory().getItemInMainHand();
                lang.send(sender, "worlds.admin.check", "player", target.getName(), "visit", visits.inVisit(target.getUniqueId()),
                        "marker", env.hasMarker(target), "snapshot", visits.hasSnapshot(target.getUniqueId()),
                        "item", hand.getType().getKey().toString(), "tool", visits.isTool(hand),
                        "restricted", ItemRestrictions.reason(hand).orElse("-"), "validation", ItemServices.validate(hand).status().name());
            }
            case "legacy" -> {
                if (args.length < 3) { lang.send(sender, "worlds.admin.usage");return true; }
                UUID id = legacyTarget(args[1]);
                if (id == null) { lang.send(sender, "worlds.admin.player-offline");return true; }
                if (visits.inVisit(id) || visits.entryPending(id)) {
                    lang.send(sender, "worlds.admin.legacy.in-progress", "player", args[1]);
                    return true;
                }
                String action = args[2].toLowerCase(Locale.ROOT);
                if (!action.equals("archive") && !action.equals("retry")) { lang.send(sender, "worlds.admin.usage");return true; }
                lang.send(sender, "worlds.admin.legacy." + env.legacy(id, action), "player", args[1]);
            }
            default -> lang.send(sender, "worlds.admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vystorm_core.admin")) return List.of();
        if (args.length == 1) return List.of("list", "visits", "restore", "check", "legacy").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("restore") || args[0].equalsIgnoreCase("check")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("legacy"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("legacy"))
            return List.of("archive", "retry").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    /** Resolves only online players and Bukkit's local cache; it never initiates a remote profile lookup. */
    private static UUID legacyTarget(String input) {
        try { return UUID.fromString(input); }
        catch (IllegalArgumentException notUuid) {
            Player online = Bukkit.getPlayerExact(input);
            if (online != null) return online.getUniqueId();
            OfflinePlayer cached = Vystorm.vystorm_core.compat.PlatformCompat.getOfflinePlayerIfCached(input);
            return cached == null ? null : cached.getUniqueId();
        }
    }
}
