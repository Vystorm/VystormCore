package Vystorm.vystorm_core.commands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Per-player cooldowns of one registration; the console and {@link #BYPASS} holders have none. */
final class Cooldowns {
    static final String BYPASS = "vystorm_core.commands.bypass-cooldown";
    private final Map<String, Long> until = new ConcurrentHashMap<>();

    boolean tryUse(CommandSender sender, CommandSpec node) {
        if (node.cooldown() == null || !(sender instanceof Player player) || player.hasPermission(BYPASS)) return true;
        long now = System.currentTimeMillis();
        String key = node.langPath() + "|" + player.getUniqueId();
        Long end = until.get(key);
        if (end != null && end > now) return false;
        until.put(key, now + node.cooldown().toMillis());
        if (until.size() > 4096) until.values().removeIf(v -> v <= now);
        return true;
    }

    long remainingSeconds(CommandSender sender, CommandSpec node) {
        if (!(sender instanceof Player player)) return 0;
        Long end = until.get(node.langPath() + "|" + player.getUniqueId());
        return end == null ? 0 : Math.max(1, (end - System.currentTimeMillis() + 999) / 1000);
    }
}
