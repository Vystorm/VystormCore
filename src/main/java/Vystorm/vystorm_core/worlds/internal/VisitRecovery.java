package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Snapshot decoding, restoration and join recovery, independent of the world provider lifecycle. */
final class VisitRecovery {
    private final VisitEnv env;
    private final WorldLookup worlds;
    private final VisitSnapshotStore store;
    private final Map<UUID, VisitImpl> visits;
    private final BiFunction<Player, Location, Boolean> teleport;
    private final Set<UUID> redirected = ConcurrentHashMap.newKeySet();
    private final WorldsWarnings warnings;

    VisitRecovery(VisitEnv env, WorldLookup worlds, VisitSnapshotStore store, Map<UUID, VisitImpl> visits,
                  BiFunction<Player, Location, Boolean> teleport) {
        this.env = env;this.worlds = worlds;this.store = store;this.visits = visits;this.teleport = teleport;
        this.warnings = new WorldsWarnings(env::log);
    }

    private boolean managed(World world) { return worlds.byWorld(world) != null; }

    /**
     * Gibt den Zustand zurück (Spieler ist schon draußen): {@code merge} = Items aus dem Besuch behalten (Review I1).
     * Speichert die Spielerdaten und löscht erst dann den Schnappschuss.
     */
    boolean restoreNow(Player player, VisitSnapshot snapshot, boolean merge) {
        UUID id = player.getUniqueId();
        String legacyKey = snapshot == null ? null : snapshot.legacyToolKey();
        Predicate<ItemStack> tool = stack -> env.isTool(stack, legacyKey);
        if (snapshot == null) {
            if (store.exists(id)) return false; // Neueres Format oder nicht lesbar: Beweise für spätere Rückgabe erhalten.
            evict(player, false, true);
            return false;
        }
        Prepared prepared = decode(player, snapshot);
        return prepared != null && restorePrepared(player, prepared, merge);
    }

    record Prepared(VisitSnapshot snapshot, ItemStack[] items, List<ItemStack> extras) {}

    Prepared decode(Player player, VisitSnapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return new Prepared(snapshot, env.codec().decode(snapshot.items()), PlayerStates.extras(snapshot, env.codec()));
        } catch (RuntimeException failure) {
            warnings.warn("decode:" + player.getUniqueId(), "Items in the snapshot of " + player.getName() + " not readable – restore stays pending: " + failure);
            return null;
        }
    }

    boolean restorePrepared(Player player, Prepared prepared, boolean merge) {
        UUID id = player.getUniqueId();
        VisitSnapshot snapshot = prepared.snapshot();
        Predicate<ItemStack> tool = stack -> env.isTool(stack, snapshot.legacyToolKey());
        List<ItemStack> back = new ArrayList<>(prepared.extras());
        List<ItemStack> received = merge ? PlayerStates.takeReceived(player, tool) : List.of();
        PlayerStates.apply(player, snapshot, prepared.items());
        env.clearMarker(player);
        back.addAll(received);
        for (ItemStack overflow : PlayerStates.give(player, back)) env.drop(player.getLocation(), overflow);
        try {
            player.saveData();
        } catch (RuntimeException failure) {
            env.log("Player data of " + player.getName() + " not saved – snapshot kept: " + failure);
            return false;
        }
        deleteSnapshot(id);
        return true;
    }

    /** Ohne (lesbaren) Schnappschuss: Werkzeug und Marker weg, Sonderzustand aufheben, aus der Welt. */
    private void evict(Player player, boolean inManaged, boolean hadMarker) {
        if (hadMarker) neutralize(player);
        else PlayerStates.stripTools(player, stack -> env.isTool(stack, null));
        env.clearMarker(player);
        if (inManaged) teleport.apply(player, env.mainSpawn());
        try { player.saveData(); } catch (RuntimeException failure) { warnings.warn("evict-save:" + player.getUniqueId(), "Player data during evacuation of " + player.getName() + " not saved: " + failure); }
        env.log("No readable snapshot for " + player.getName() + ": tool removed, visit state cleared (check the *.broken-* file in visits/).");
    }

    /** Removes visit privileges and tools, retaining inventory gifts and all recovery evidence. */
    void neutralize(Player player) {
        PlayerStates.stripTools(player, stack -> env.isTool(stack, null));
        player.setGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private RecoveryDecision pendingIfEvidence(Player player) {
        // An unreadable/missing snapshot alone is not proof that the saved inventory is a visit inventory.
        if (env.hasMarker(player) || PlayerStates.hasTools(player, stack -> env.isTool(stack, null)) || managed(player.getWorld()))
            return pending(player);
        return RecoveryDecision.PENDING;
    }

    private RecoveryDecision pending(Player player) {
        if (!env.hasMarker(player)) env.setMarker(player, "pending-recovery");
        neutralize(player);
        return RecoveryDecision.PENDING;
    }

    void deleteSnapshot(UUID id) {
        try { store.delete(id); } catch (IOException failure) { env.log("Snapshot " + id + " not deleted: " + failure); }
    }

    private Location returnOf(VisitSnapshot s) {
        World world = s.returnWorld() == null ? null : env.world(s.returnWorld());
        if (world == null || managed(world)) return env.mainSpawn();
        return new Location(world, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
    }

    // ------------------------------------------------------------------ Erholung

    /**
     * Asynchrones Login-Ereignis: Spieler mit Schnappschuss, die in einer Plugin-Welt erscheinen würden, erscheinen am
     * Rückkehrort (gemerkt für {@link #recover}). Liefert {@code null} = nicht ändern.
     */
    public Location spawnLocation(UUID id, Location current) {
        boolean inManaged = current != null && managed(current.getWorld());
        if (!store.exists(id)) return inManaged ? env.mainSpawn() : null;
        if (!inManaged) return null;
        Optional<VisitSnapshot> snapshot = store.load(id);
        redirected.add(id);
        return snapshot.map(this::returnOf).orElseGet(env::mainSpawn);
    }

    /** Join (und Admin-Wiederherstellung): siehe {@link RecoveryDecision}. Immer aktiv, auch wenn das Besitzer-Plugin fehlt (Review I2). */
    public RecoveryDecision recover(Player player) {
        UUID id = player.getUniqueId();
        if (visits.containsKey(id)) return RecoveryDecision.NOTHING;
        if (env.pendingMigration(id) && !store.exists(id)) {
            env.log("Legacy snapshot blocks player " + id + " (" + player.getName() + "); use /vworlds legacy <player> archive|retry");
            return pendingIfEvidence(player);
        }
        boolean wasRedirected = redirected.remove(id);
        Optional<VisitSnapshot> snapshot = store.exists(id) ? store.load(id) : Optional.empty();
        if (snapshot.isEmpty() && store.exists(id)) {
            env.log("Snapshot of " + player.getName() + " currently not readable – file and visit marker are kept.");
            return pendingIfEvidence(player);
        }
        String legacyKey = snapshot.map(VisitSnapshot::legacyToolKey).orElse(null);
        boolean marker = env.hasMarker(player);
        boolean tools = PlayerStates.hasTools(player, stack -> env.isTool(stack, legacyKey));
        boolean inManaged = managed(player.getWorld());
        RecoveryDecision decision = RecoveryDecision.decide(snapshot.isPresent(), marker, tools, inManaged, wasRedirected);
        switch (decision) {
            case NOTHING, PENDING -> { }
            case DISCARD -> {
                // Auch nach einer fehlgeschlagenen Rückgabe kann nur der Arbeitsspeicher schon draußen sein.
                // Erst sicher speichern, damit ein weiterer Absturz nicht den einzigen Rückgabe-Beleg verliert.
                try { player.saveData(); }
                catch (RuntimeException failure) {
                    env.log("Player data of " + player.getName() + " not saved – snapshot kept: " + failure);
                    return RecoveryDecision.PENDING;
                }
                deleteSnapshot(id);
                env.log("Snapshot of " + player.getName() + " discarded: player data is already outside the visit (nothing restored twice).");
            }
            case EVICT -> evict(player, inManaged, marker);
            case RESTORE -> {
                VisitSnapshot s = snapshot.get();
                Prepared prepared = decode(player, s);
                if (prepared == null) return pending(player);
                Location back = returnOf(s);
                if (inManaged || !near(player.getLocation(), back)) {
                    boolean moved = teleport.apply(player, back) && !managed(player.getWorld());
                    if (!moved) moved = teleport.apply(player, env.mainSpawn()) && !managed(player.getWorld());
                    if (!moved && managed(player.getWorld())) {
                        env.log("Restore for " + player.getName() + " postponed: teleport out of the plugin world failed.");
                        return pending(player);
                    }
                }
                if (!restorePrepared(player, prepared, marker || tools)) return env.hasMarker(player) ? pending(player) : RecoveryDecision.PENDING;
                env.message(player, "worlds.visit.restored", "world", s.worldName().isEmpty() ? s.worldId() : s.worldName());
                env.log("Visit snapshot of " + player.getName() + " (" + s.worldId() + ") restored.");
            }
        }
        return decision;
    }

    private static boolean near(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) < 4;
    }

}
