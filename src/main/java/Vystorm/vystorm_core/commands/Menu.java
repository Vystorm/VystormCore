package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.main;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

public final class Menu implements TabExecutor {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (!(sender instanceof Player player)) { lang.send(sender, "menu.players-only"); return true; }
        var gui = main.getApi().gui();
        if (args.length == 0 && main.getPlugin().getConfig().getBoolean("menu.screen", true)) {
            // Vystorm Screens (0.57.0): the main menu as icon tiles; without a screen the previous menu opens.
            var result = new Vystorm.vystorm_core.screen.internal.MenuScreen(main.getPlugin(), gui).open(player, p -> openClassic(p, gui));
            // 0.60.0: say why the classic menu opened instead of the screen (riding, falling, water, just hit …).
            if (!result.opened() && result.refusedKey() != null
                    && !Vystorm.vystorm_core.screen.internal.ScreenRuntime.silentRefusal(result.refusedKey())) lang.send(player, result.refusedKey());
        }
        else if (args.length == 0) {
            // Natives Panel (ohne Mod: dessen fallback = openDirectory(player, 0)); nicht registriert → wie bisher.
            var panels = Vystorm.vystorm_core.panel.Panels.of(main.getPlugin());
            if (Vystorm.vystorm_core.panel.internal.PanelRegistry.find(panels.id(Vystorm.vystorm_core.panel.internal.MenuPanels.NAME)).isPresent())
                panels.open(player, Vystorm.vystorm_core.panel.internal.MenuPanels.NAME);
            else gui.openDirectory(player, 0);
        }
        else if (args.length == 1 && args[0].equalsIgnoreCase("search")) Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.open(player, gui);
        else if (args.length == 2 && args[0].equalsIgnoreCase("access")) {
            if (!player.hasPermission("vystorm_core.admin")) { lang.send(player, "menu.access.no-permission"); return true; }
            if (!Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.DIAGNOSTICS)) { lang.send(player, "feature-off"); return true; }
            Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) { lang.send(player, "menu.access.online-only"); return true; }
            for (var access : gui.inspectMenus(target)) lang.send(player, "menu.access.line", "menu", access.key(),
                    "player", target.getName(), "reason", Vystorm.vystorm_core.lang.Localization.get().resolve(player, access.reason()));
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("player")) openPlayerMenu(player, args[1]);
        else if (args.length == 1 && args[0].equalsIgnoreCase("news")) {
            if (!Vystorm.vystorm_core.news.News.open(player)) lang.send(player, "news.unavailable");
        }
        else if (args.length >= 1 && args.length <= 2 && args[0].equalsIgnoreCase("profile")) Vystorm.vystorm_core.profile.internal.ProfileService.command(player, args.length == 2 ? args[1] : null);
        else if (args.length == 1 && args[0].equalsIgnoreCase("inbox")) {
            if (!Vystorm.vystorm_core.inbox.Inbox.open(player)) lang.send(player, "inbox.unavailable");
        }
        else if (args.length == 1 && args[0].equalsIgnoreCase("list")) sender.sendMessage(String.join(", ", gui.menuKeys(player)));
        else if (args.length == 1 && (args[0].equalsIgnoreCase("einstellungen") || args[0].equalsIgnoreCase("settings"))) main.getApi().settings().openOverview(player);
        else if (args.length != 1 || !gui.openMenu(player, args[0])) {
            // 0.65.0: menus from files answer to menus:<id> and to their bare id; registered keys keep the first say
            var files = Vystorm.vystorm_core.menus.internal.FileMenuService.shared();
            String fileMenu = args.length == 1 && files != null ? files.idOf(args[0]) : null;
            if (fileMenu != null) { files.openOrTell(player, fileMenu); return true; }
            var legacy = args.length == 1 ? gui.getInventory(args[0], player) : null;
            if (legacy != null) player.openInventory(legacy);
            else lang.send(sender, "menu.unavailable");
        }
        return true;
    }

    /** {@code /menu player <name>}: the shift-right-click player menu by command (same entries, same permission). */
    private static void openPlayerMenu(Player player, String name) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (!player.hasPermission(Vystorm.vystorm_core.playermenu.PlayerMenu.PERMISSION)) { lang.send(player, "player-menu.no-permission"); return; }
        if (!Vystorm.vystorm_core.playermenu.internal.PlayerMenuTrigger.enabled(main.getPlugin())) { lang.send(player, "player-menu.disabled"); return; }
        Player target = org.bukkit.Bukkit.getPlayerExact(name);
        if (target == null || target.hasMetadata("NPC") || !player.canSee(target)) { lang.send(player, "player-menu.offline", "player", name); return; }
        if (target.equals(player)) { lang.send(player, "player-menu.self"); return; }
        if (!Vystorm.vystorm_core.playermenu.PlayerMenu.open(player, target)) lang.send(player, "player-menu.empty", "player", target.getName());
    }

    private static void openClassic(Player player, Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface gui) {
        var panels = Vystorm.vystorm_core.panel.Panels.of(main.getPlugin());
        if (Vystorm.vystorm_core.panel.internal.PanelRegistry.find(panels.id(Vystorm.vystorm_core.panel.internal.MenuPanels.NAME)).isPresent())
            panels.open(player, Vystorm.vystorm_core.panel.internal.MenuPanels.NAME);
        else gui.openDirectory(player, 0);
    }

    /** Subcommands handled in {@link #onCommand} besides menu keys; {@code access} needs {@code vystorm_core.admin}, {@code player} {@code vystorm_core.playermenu}. */
    static final List<String> SUBCOMMANDS = List.of("search", "list", "settings", "einstellungen");

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) return List.of();
        boolean admin = player.hasPermission("vystorm_core.admin");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBCOMMANDS);
            if (admin && Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.DIAGNOSTICS)) out.add("access");
            if (player.hasPermission(Vystorm.vystorm_core.playermenu.PlayerMenu.PERMISSION)) out.add("player");
            if (player.hasPermission(Vystorm.vystorm_core.news.News.PERMISSION)) out.add("news");
            if (player.hasPermission(Vystorm.vystorm_core.inbox.Inbox.PERMISSION)) out.add("inbox");
            if (player.hasPermission(Vystorm.vystorm_core.profile.Profiles.PERMISSION)) out.add("profile");
            out.addAll(main.getApi().gui().menuKeys(player));
            var files = Vystorm.vystorm_core.menus.internal.FileMenuService.shared();
            if (files != null) out.addAll(files.idsFor(player));
        } else if (args.length == 2 && admin && args[0].equalsIgnoreCase("access")) {
            player.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            player.getServer().getOnlinePlayers().stream().filter(player::canSee).forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("player") && player.hasPermission(Vystorm.vystorm_core.playermenu.PlayerMenu.PERMISSION)) {
            player.getServer().getOnlinePlayers().stream().filter(p -> !p.equals(player) && player.canSee(p)).forEach(p -> out.add(p.getName()));
        }
        String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return out.stream().filter(k -> k.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).distinct().toList();
    }
}
