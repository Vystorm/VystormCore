package Vystorm.vystorm_core.web.internal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit-Umsetzung von {@link PlayerAccess}. Bukkit ist nicht threadsicher; deshalb berechnet {@link #refresh()} (jede
 * Sekunde auf dem Server-Thread) für alle Spieler mit Web-Sitzung einen Schnappschuss aller benötigten Berechtigungen,
 * den Web-Threads lockfrei lesen. Fehlt ein Knoten im Schnappschuss, wird er einmalig synchron auf dem Server-Thread
 * nachgeladen (mit Timeout, bei Fehlschlag: verweigert).
 *
 * <p>Offline-Spieler behalten die zuletzt online gemessenen Werte; ohne solche gelten die Bukkit-Standardwerte der
 * Berechtigung (abhängig vom OP-Status). Damit ein im Spiel geholter Link, der erst nach dem Ausloggen eingelöst wird, nicht
 * auf die Standardwerte fällt (z. B. ein per LuckPerms negierter {@code default: true}-Knoten), wird beim Ausstellen
 * {@link #capture} aufgerufen und Inhaber offener Links zählen beim Refresh mit (0.18.1). Gebannte Spieler verlieren beim
 * nächsten Refresh alle Sitzungen. Core-intern.
 *
 * @since 0.17.0
 */
public final class BukkitPlayerAccess implements PlayerAccess {
    private record Snapshot(boolean live, Map<String, Boolean> permissions) {}

    private final Plugin core;
    private final Supplier<Set<String>> nodes;
    private final Supplier<Set<UUID>> sessionPlayers;
    private final Consumer<UUID> banned;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();

    public BukkitPlayerAccess(Plugin core, Supplier<Set<String>> nodes, Supplier<Set<UUID>> sessionPlayers, Consumer<UUID> banned) {
        this.core = core;
        this.nodes = nodes;
        this.sessionPlayers = sessionPlayers;
        this.banned = banned;
    }

    public void joined(UUID player) { online.add(player); }
    public void quit(UUID player) { online.remove(player); }

    /** Nur Server-Thread: Online-Liste, Bann-Status und Berechtigungs-Schnappschüsse neu berechnen. */
    public void refresh() {
        online.clear();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        Set<UUID> players = sessionPlayers.get();
        snapshots.keySet().retainAll(players);
        Set<String> needed = nodes.get();
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                if (offline.isBanned()) { banned.accept(id); snapshots.remove(id); continue; }
                Snapshot last = snapshots.get(id);
                if (last == null || !last.live()) snapshots.put(id, new Snapshot(false, compute(id, needed)));
                continue;
            }
            if (player.isBanned()) { banned.accept(id); snapshots.remove(id); continue; }
            snapshots.put(id, new Snapshot(true, compute(id, needed)));
        }
    }

    /**
     * Nur Server-Thread: misst die Rechte eines gerade online befindlichen Spielers sofort (beim Ausstellen eines Links).
     * Ohne Wirkung, wenn er offline ist.
     *
     * @since 0.18.1
     */
    public void capture(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        online.add(id);
        snapshots.put(id, new Snapshot(true, compute(id, nodes.get())));
    }

    @Override public boolean online(UUID player) { return online.contains(player); }

    @Override public boolean has(UUID player, String node) {
        Snapshot snapshot = snapshots.get(player);
        Boolean value = snapshot == null ? null : snapshot.permissions().get(node);
        if (value != null) return value;
        try {
            boolean computed = sync(() -> compute(player, Set.of(node)).get(node), 2000);
            snapshots.compute(player, (k, old) -> {
                Map<String, Boolean> merged = new ConcurrentHashMap<>(old == null ? Map.of() : old.permissions());
                merged.put(node, computed);
                return new Snapshot(old != null && old.live(), Map.copyOf(merged));
            });
            return computed;
        } catch (Exception failed) {
            return false;
        }
    }

    @Override public <T> T sync(Callable<T> task, long timeoutMs) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        if (!core.isEnabled()) throw new TimeoutException("core disabled");
        Future<T> future = Bukkit.getScheduler().callSyncMethod(core, task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(false);
            throw timeout;
        }
    }

    /** Nur Server-Thread. */
    private static Map<String, Boolean> compute(UUID id, Set<String> needed) {
        Map<String, Boolean> out = new ConcurrentHashMap<>();
        Player player = Bukkit.getPlayer(id);
        OfflinePlayer offline = player != null ? player : Bukkit.getOfflinePlayer(id);
        boolean op = offline.isOp();
        for (String node : needed) {
            boolean value;
            if (player != null) value = player.hasPermission(node);
            else {
                Permission permission = Bukkit.getPluginManager().getPermission(node);
                PermissionDefault def = permission == null ? Permission.DEFAULT_PERMISSION : permission.getDefault();
                value = def.getValue(op);
            }
            out.put(node, value);
        }
        return Map.copyOf(out);
    }
}
