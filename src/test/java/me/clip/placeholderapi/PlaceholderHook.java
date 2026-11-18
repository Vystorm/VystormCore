package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Hand-written minimal stand-in for PlaceholderAPI's class of the same name: only the documented signatures that
 * Core's bridge uses, no code of PlaceholderAPI. It lives in the test sources, is never shipped, and serves two
 * purposes: Core's expansion class ({@code src/bridges}) is compiled against it, and the tests use it as a fake
 * PlaceholderAPI. On a server the real plugin provides these classes.
 */
public abstract class PlaceholderHook {
    public String onRequest(OfflinePlayer player, String params) {
        if (player != null && player.isOnline()) return onPlaceholderRequest(player.getPlayer(), params);
        return onPlaceholderRequest(null, params);
    }

    public String onPlaceholderRequest(Player player, String params) { return null; }
}
