package Vystorm.vystorm_core.screen.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 0.71.1: {@code /vadmin visible <player>} (console and in game): makes a player visible again whom an older Core left
 * invisible (or anyone with the invisible flag): closes their screen, clears the flag, resets their player weather and
 * tells them. Permission: {@code vystorm_core.admin} (checked by {@code /vadmin}).
 */
public final class ScreenVisibleAdmin {
    private ScreenVisibleAdmin() { }

    public static void handle(CommandSender sender, String[] args) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (args.length < 2) { lang.send(sender, "screen.visible.usage"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { lang.send(sender, "screen.visible.not-found", "player", args[1]); return; }
        // the game shows spectators invisible (only their head is drawn otherwise); Core checks them again when they leave it
        if (target.getGameMode() == org.bukkit.GameMode.SPECTATOR) { lang.send(sender, "screen.visible.spectator", "player", target.getName()); return; }
        boolean was = ScreenRuntime.get().makeVisible(target);
        if (sender != target) lang.send(sender, was ? "screen.visible.done" : "screen.visible.already", "player", target.getName());
    }

    public static List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 2) return out;
        String prefix = args[1].toLowerCase(Locale.ROOT);
        for (Player online : Bukkit.getOnlinePlayers()) if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(online.getName());
        return out;
    }
}
