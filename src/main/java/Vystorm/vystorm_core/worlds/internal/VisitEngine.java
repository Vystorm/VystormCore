package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.worlds.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Besuche: betreten, verlassen, Absturz-Erholung, Sichtbarkeit, Befehls- und Teleport-Regeln. Server-Thread, außer
 * {@link #inVisit}, {@link #hasSnapshot} und {@link #spawnLocation} (asynchrones Login-Ereignis).
 *
 * <p>Reihenfolge beim Verlassen (Review C2): <b>erst Teleport hinaus, dann Zustand zurück</b>, danach Spielerdaten
 * speichern und erst dann den Schnappschuss löschen. Scheitert der Teleport, bleibt der Besuch bestehen (Werkzeuge,
 * Befehlssperre). Ob die Spielerdaten im Besuchsstand sind, entscheidet der PDC-Marker (siehe {@link RecoveryDecision}).</p>
 */
public final class VisitEngine implements VisitService {
    /** Rückgabecodes von {@link #restore}. */
    public static final String RESTORE_VISIT_ENDED = "visit-ended", RESTORE_RESTORED = "restored", RESTORE_DISCARDED = "discarded",
            RESTORE_EVICTED = "evicted", RESTORE_NOTHING = "nothing", RESTORE_PENDING = "pending";

    private final VisitEnv env;
    private final WorldLookup worlds;
    private final VisitSnapshotStore store;
    private final Map<UUID, VisitImpl> visits = new ConcurrentHashMap<>();
    private final Set<UUID> ownTeleports = new HashSet<>();
    private final Map<UUID, Long> lastEntry = new HashMap<>();
    private final VisitRecovery recovery;
    private final VisitVisibility visibility;
    private final ExecutorService snapshotWrites;
    private final Map<UUID, Admission> admissions = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private static final long ENTRY_TIMEOUT_MILLIS = 2500;

    private static final class Admission {
        final Player player;
        final ManagedWorldImpl world;
        final Location origin, back, arrival;
        final long started;
        final byte[] bytes;
        final UUID playerId;
        final String items, gameMode;
        final int totalExperience, level;
        final float experience;
        volatile boolean cancelled;
        Admission(Player player, ManagedWorldImpl world, Location origin, Location back, Location arrival, long started,
                  VisitSnapshot snapshot, byte[] bytes) {
            this.player = player;this.world = world;this.origin = origin;this.back = back;this.arrival = arrival;
            this.started = started;this.bytes = bytes;
            playerId = snapshot.player();items = snapshot.items();gameMode = snapshot.gameMode();
            totalExperience = player.getTotalExperience();level = player.getLevel();experience = player.getExp();
        }

        /** Server thread only. Compare valuables/identity, never naturally ticking snapshot scalars. */
        boolean matches(Player player, ItemCodec codec) {
            return playerId.equals(player.getUniqueId()) && gameMode.equals(player.getGameMode().name())
                    && totalExperience == player.getTotalExperience() && level == player.getLevel()
                    && Float.compare(experience, player.getExp()) == 0
                    && PlayerStates.transientItems(player).isEmpty()
                    && items.equals(codec.encode(player.getInventory().getContents()));
        }
    }

    private static ExecutorService snapshotExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Vystorm-visit-snapshots");
            thread.setDaemon(true);
            return thread;
        });
    }

    public VisitEngine(VisitEnv env, WorldLookup worlds, VisitSnapshotStore store) {
        this(env, worlds, store, snapshotExecutor());
    }

    VisitEngine(VisitEnv env, WorldLookup worlds, VisitSnapshotStore store, ExecutorService snapshotWrites) {
        this.env = env;this.worlds = worlds;this.store = store;this.snapshotWrites = snapshotWrites;
        recovery = new VisitRecovery(env, worlds, store, visits, this::teleportOwn);
        visibility = new VisitVisibility(env, worlds, visits);
    }

    private void requireThread() {
        if (!env.primaryThread()) throw new IllegalStateException("visits need the server thread");
    }

    private boolean managed(World world) { return worlds.byWorld(world) != null; }

    // ------------------------------------------------------------------ Abfragen

    boolean entryPending(UUID player) { return admissions.containsKey(player); }

    @Override public boolean inVisit(UUID player) { return visits.containsKey(player); }

    @Override public Optional<Visit> visit(UUID player) { return Optional.ofNullable(visits.get(player)); }

    @Override public Collection<Visit> visits(String worldId) {
        List<Visit> result = new ArrayList<>();
        for (VisitImpl v : visits.values()) if (v.worldId().equals(worldId)) result.add(v);
        return result;
    }

    @Override public boolean hasSnapshot(UUID player) { return store.exists(player); }

    @Override public boolean isTool(ItemStack stack) { return !PlayerStates.empty(stack) && env.isTool(stack, null); }

    boolean ownTeleport(UUID player) { return ownTeleports.contains(player); }

    /** Welt des laufenden Besuchs oder {@code null}. */
    public ManagedWorldImpl visitWorld(Player player) {
        VisitImpl visit = visits.get(player.getUniqueId());
        return visit == null ? null : worlds.byId(visit.worldId());
    }

    // ------------------------------------------------------------------ Betreten

    @Override
    public VisitResult enter(Player player, String worldId, Location arrival) {
        long started = System.nanoTime();
        try { return enterRequest(player, worldId, arrival); }
        finally { if (Boolean.getBoolean("vystorm.worlds.entryTiming")) env.log("[EntryTiming] enter ns=" + (System.nanoTime() - started)); }
    }

    private VisitResult enterRequest(Player player, String worldId, Location arrival) {
        requireThread();
        ManagedWorldImpl world = worlds.byId(worldId);
        if (stopped || world == null || world.world() == null) return VisitResult.NO_WORLD;
        if (arrival == null || !world.world().equals(arrival.getWorld())) return VisitResult.WRONG_TARGET;
        if (!player.isOnline()) return VisitResult.OFFLINE;
        UUID id = player.getUniqueId();
        if (admissions.containsKey(id) || visits.containsKey(id) || ownTeleports.contains(id) || managed(player.getWorld())) return VisitResult.ALREADY;
        if (store.exists(id) || env.hasMarker(player) || env.pendingMigration(id)) return VisitResult.PENDING_RECOVERY;
        if (player.isDead()) return VisitResult.DEAD;
        if (player.isInsideVehicle()) return VisitResult.VEHICLE;
        if (player.isGliding()) return VisitResult.GLIDING;
        if (player.isSleeping()) return VisitResult.SLEEPING;
        VisitPolicy policy = world.spec().policy();
        long now = env.now();
        long retention = worlds.all().stream().mapToLong(w -> w.spec().policy().cooldownSeconds() * 1000L).max().orElse(0);
        lastEntry.entrySet().removeIf(entry -> now - entry.getValue() >= retention);
        Long last = lastEntry.get(id);
        if (last != null && now - last < policy.cooldownSeconds() * 1000L) return VisitResult.COOLDOWN;
        Location origin = player.getLocation();
        if (blockedRegion(origin, policy)) return VisitResult.BLOCKED_REGION;
        Location back = policy.returnLocation(player, origin.clone());
        // Closing returns cursor/crafting items through Bukkit's normal inventory handling. Never remove them
        // into an in-memory holding list while a background write could be lost to a crash.
        player.closeInventory();
        Admission admission;
        try {
            VisitSnapshot snapshot = PlayerStates.capture(player, env.codec(), worldId,
                    world.spec().worldName(), world.owner().getName(), back, now, List.of());
            if (policy.isolateExperience()) snapshot = snapshot.withExperience(player.getLevel(), player.getTotalExperience(), player.getExp());
            byte[] bytes = VisitSnapshotStore.serialize(snapshot);
            admission = new Admission(player, world, origin.clone(), back.clone(), arrival.clone(), now, snapshot, bytes);
        } catch (RuntimeException failure) {
            env.log("Snapshot for " + player.getName() + " not captured – visit refused: " + failure);
            return VisitResult.SNAPSHOT_FAILED;
        }
        admissions.put(id, admission);
        try {
            snapshotWrites.execute(() -> writeAdmission(id, admission));
            env.later(50, () -> {
                if (admissions.get(id) == admission && !admission.cancelled) cancelAdmission(id, admission, true);
            });
        } catch (RejectedExecutionException failure) {
            admissions.remove(id, admission);
            return VisitResult.SNAPSHOT_FAILED;
        }
        return VisitResult.OK;
    }

    private void writeAdmission(UUID id, Admission admission) {
        Exception failure = null;
        long started = System.nanoTime();
        try {
            if (!admission.cancelled && !stopped) store.saveBytes(id, admission.bytes);
        } catch (IOException | RuntimeException problem) { failure = problem; }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (millis > 50) env.warn("Durable snapshot write for " + id + " took " + millis + " ms");
        if (stopped || admission.cancelled) {
            deleteAdmission(id, admission);
            return;
        }
        Exception result = failure;
        dispatch(() -> finishAdmission(id, admission, result));
    }

    private void dispatch(Runnable task) {
        if (stopped) return;
        if (env.primaryThread()) task.run();
        else env.nextTick(task);
    }

    private void finishAdmission(UUID id, Admission admission, Exception failure) {
        long started = System.nanoTime();
        try { finishAdmissionNow(id, admission, failure); }
        finally { if (Boolean.getBoolean("vystorm.worlds.entryTiming")) env.log("[EntryTiming] finish ns=" + (System.nanoTime() - started)); }
    }

    private void finishAdmissionNow(UUID id, Admission admission, Exception failure) {
        if (stopped || admissions.get(id) != admission || admission.cancelled) return;
        Player player = admission.player;
        ManagedWorldImpl world = admission.world;
        boolean matches = failure == null && player.isOnline() && !player.isDead()
                && !player.isInsideVehicle() && !player.isGliding() && !player.isSleeping()
                && env.now() - admission.started < ENTRY_TIMEOUT_MILLIS
                && worlds.byId(world.id()) == world && world.world() != null && world.owner().isEnabled()
                && !env.hasMarker(player) && !env.pendingMigration(id)
                && samePosition(player.getLocation(), admission.origin);
        try {
            matches = matches && admission.matches(player, env.codec());
        } catch (RuntimeException problem) { matches = false;failure = problem; }
        if (!matches) {
            if (failure != null) env.log("Snapshot for " + id + " failed: " + failure);
            cancelAdmission(id, admission, true);
            return;
        }
        admissions.remove(id, admission);
        // Durable submit-time snapshot AND unchanged valuables/identity: only now may Core mutate the player.
        // Restoring scalars (effects, health, food, air, etc.) can rewind the short admission window.
        env.setMarker(player, world.id());
        PlayerStates.prepare(player, world.spec().policy());
        VisitImpl visit = new VisitImpl(id, world.id(), admission.started, admission.back);
        visits.put(id, visit);
        if (world.spec().policy().cooldownSeconds() > 0) lastEntry.put(id, env.now());
        giveTools(player, world);
        hook(world, "entering", hooks -> hooks.entering(player, visit));
        ownTeleports.add(id);
        try {
            env.teleportAsync(player, admission.arrival).whenComplete((ok, problem) ->
                    env.nextTick(() -> afterEntryTeleport(player, world, visit, Boolean.TRUE.equals(ok) && problem == null)));
        } catch (RuntimeException problem) { afterEntryTeleport(player, world, visit, false); }
    }

    private static boolean samePosition(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= 0.0001
                && Math.abs(a.getYaw() - b.getYaw()) <= 0.01f && Math.abs(a.getPitch() - b.getPitch()) <= 0.01f;
    }

    private void cancelAdmission(UUID id, Admission admission, boolean notify) {
        if (admission.cancelled) return;
        admission.cancelled = true;
        if (notify && admission.player.isOnline()) env.message(admission.player, "worlds.visit.enter-aborted");
        try { snapshotWrites.execute(() -> deleteAdmission(id, admission)); }
        catch (RejectedExecutionException ignored) { /* Shutdown: unmarked files are discarded at recovery. */ }
    }

    private void deleteAdmission(UUID id, Admission admission) {
        try { store.delete(id); }
        catch (IOException failure) { env.warn("Abandoned snapshot " + id + " could not be deleted: " + failure); }
        dispatch(() -> admissions.remove(id, admission));
    }

    /** Death or an external teleport invalidates the pre-admission capture even if the player returns later. */
    public void invalidateEntry(Player player) {
        Admission admission = admissions.get(player.getUniqueId());
        if (admission != null) cancelAdmission(player.getUniqueId(), admission, false);
    }

    /** Fail-closed (Review-Minor): scheitert die Regionsabfrage, gilt der Ort als gesperrt. */
    private boolean blockedRegion(Location location, VisitPolicy policy) {
        if (policy.blockedRegionKinds().isEmpty()) return false;
        try {
            for (Region region : env.regionsAt(location))
                if (policy.blockedRegionKinds().contains(region.kind().toLowerCase(Locale.ROOT))) return true;
            return false;
        } catch (RuntimeException | LinkageError failure) {
            env.log("Region check failed – entry refused: " + failure);
            return true;
        }
    }

    private void afterEntryTeleport(Player player, ManagedWorldImpl world, VisitImpl visit, boolean ok) {
        UUID id = player.getUniqueId();
        ownTeleports.remove(id);
        if (visits.get(id) != visit) {
            // In der Zwischenzeit beendet (Quit, Shutdown, Admin): nie in der Welt zurücklassen.
            if (player.isOnline() && managed(player.getWorld())) teleportOwn(player, safeReturn(visit.returnLocation()));
            return;
        }
        if (visit.phase != Visit.Phase.ENTERING) return;   // während des Eintritts verlassen: der Austritt übernimmt
        if (ok && player.isOnline() && world.world() != null && world.world().equals(player.getWorld())) {
            visit.phase = Visit.Phase.ACTIVE;
            hook(world, "arrived", hooks -> hooks.arrived(player, visit));
            refreshVisibility(world.id());
            return;
        }
        env.log("Teleport into " + world.id() + " failed for " + player.getName() + " – state is being restored.");
        if (!player.isOnline()) return;
        finishSync(player, visit, ExitReason.FAILED_ENTRY);
        env.message(player, "worlds.visit.enter-failed");
    }

    // ------------------------------------------------------------------ Verlassen

    @Override
    public boolean exit(Player player, ExitReason reason) {
        requireThread();
        Admission admission = admissions.get(player.getUniqueId());
        if (admission != null) { cancelAdmission(player.getUniqueId(), admission, false);return true; }
        VisitImpl visit = visits.get(player.getUniqueId());
        if (visit == null) return false;
        switch (reason) {
            case QUIT, SHUTDOWN, REBUILD, FAILED_ENTRY -> finishSync(player, visit, reason);
            case WORLD_CHANGE -> complete(player, visit, reason, true);
            default -> { if (visit.phase != Visit.Phase.LEAVING) finishAsync(player, visit, reason); }
        }
        return true;
    }

    /** Synchron hinaus (Quit, Shutdown, Neuaufbau): erst Teleport, dann Zustand. Scheitert der Teleport, bleibt alles für den Join. */
    private void finishSync(Player player, VisitImpl visit, ExitReason reason) {
        boolean moved = !managed(player.getWorld());
        if (!moved) moved = teleportOwn(player, safeReturn(visit.returnLocation())) && !managed(player.getWorld());
        if (!moved) moved = teleportOwn(player, env.mainSpawn()) && !managed(player.getWorld());
        complete(player, visit, reason, moved);
    }

    private void finishAsync(Player player, VisitImpl visit, ExitReason reason) {
        UUID id = player.getUniqueId();
        VisitRecovery.Prepared prepared = recovery.decode(player, store.load(id).orElse(null));
        if (prepared == null) {
            visit.phase = Visit.Phase.ACTIVE;
            env.message(player, "worlds.visit.restore-failed");
            return;
        }
        visit.phase = Visit.Phase.LEAVING;
        ownTeleports.add(id);
        env.teleportAsync(player, safeReturn(visit.returnLocation())).whenComplete((ok, failure) -> env.nextTick(() -> {
            ownTeleports.remove(id);
            if (visits.get(id) != visit || !player.isOnline()) return;   // Quit/Shutdown hat übernommen
            boolean moved = Boolean.TRUE.equals(ok) && failure == null && !managed(player.getWorld());
            if (!moved) moved = teleportOwn(player, env.mainSpawn()) && !managed(player.getWorld());
            if (!moved) {
                visit.phase = Visit.Phase.ACTIVE;
                env.log("Return from " + visit.worldId() + " failed for " + player.getName() + " – visit stays active.");
                env.message(player, "worlds.visit.return-failed");
                return;
            }
            complete(player, visit, reason, true, prepared);
        }));
    }

    /** Besuch beenden; {@code restore}: Spieler ist draußen, Zustand jetzt zurückgeben. */
    private void complete(Player player, VisitImpl visit, ExitReason reason, boolean restore) {
        complete(player, visit, reason, restore, null);
    }

    private void complete(Player player, VisitImpl visit, ExitReason reason, boolean restore, VisitRecovery.Prepared prepared) {
        UUID id = player.getUniqueId();
        visits.remove(id, visit);
        ManagedWorldImpl world = worlds.byId(visit.worldId());
        if (world != null) hook(world, "leaving", hooks -> hooks.leaving(player, visit, reason));
        visibility.showAll(player);
        if (restore) {
            if (env.hasMarker(player)) {
                if (prepared != null) recovery.restorePrepared(player, prepared, true);
                else recovery.restoreNow(player, store.load(id).orElse(null), true);
            }
            else { recovery.deleteSnapshot(id);env.log(player.getName() + ": state was already restored – nothing restored twice."); }
        } else {
            env.log(player.getName() + " could not leave " + visit.worldId() + " (" + reason + ") – state stays saved, restore on the next join.");
        }
        // A cleared marker means state was applied, even when saveData failed. Never neutralize that restored state.
        if (player.isOnline() && env.hasMarker(player)) recovery.neutralize(player);
        if (world != null) refreshVisibility(world.id());
    }

    private boolean teleportOwn(Player player, Location to) {
        if (to == null) return false;
        UUID id = player.getUniqueId();
        ownTeleports.add(id);
        try { return env.teleport(player, to); }
        catch (RuntimeException failure) { env.log("Teleport fehlgeschlagen: " + failure);return false; }
        finally { ownTeleports.remove(id); }
    }

    /** Rückkehrort, falls seine Welt geladen und keine Plugin-Welt ist; sonst Hauptspawn. */
    private Location safeReturn(Location location) {
        if (location == null || location.getWorld() == null) return env.mainSpawn();
        World world = env.world(location.getWorld().getName());
        if (world == null || managed(world)) return env.mainSpawn();
        return location;
    }

    public Location spawnLocation(UUID id, Location current) { return recovery.spawnLocation(id, current); }

    public RecoveryDecision recover(Player player) { requireThread();return admissions.containsKey(player.getUniqueId()) ? RecoveryDecision.PENDING : recovery.recover(player); }

    @Override
    public String restore(Player player) {
        requireThread();
        if (admissions.containsKey(player.getUniqueId())) return RestoreOutcome.IN_PROGRESS.code();
        VisitImpl visit = visits.get(player.getUniqueId());
        if (visit != null) {
            if (visit.phase == Visit.Phase.LEAVING || visit.phase == Visit.Phase.ENTERING) return RestoreOutcome.IN_PROGRESS.code();
            exit(player, ExitReason.ADMIN);
            return visit.phase == Visit.Phase.LEAVING ? RESTORE_VISIT_ENDED : RESTORE_PENDING;
        }
        return switch (recover(player)) {
            case RESTORE -> RESTORE_RESTORED;
            case DISCARD -> RESTORE_DISCARDED;
            case EVICT -> RESTORE_EVICTED;
            case NOTHING -> RESTORE_NOTHING;
            case PENDING -> RESTORE_PENDING;
        };
    }

    // ------------------------------------------------------------------ Werkzeuge, Tod, Respawn

    private void giveTools(Player player, ManagedWorldImpl world) {
        Map<Integer, ItemStack> items;
        try { items = world.spec().policy().toolKit().items(player); }
        catch (RuntimeException | LinkageError failure) { env.log("Werkzeuge von " + world.id() + " fehlgeschlagen: " + failure);items = Map.of(); }
        if (items == null) items = Map.of();
        PlayerInventory inventory = player.getInventory();
        PlayerStates.stripTools(player, this::isTool);
        int size = inventory.getSize();
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            int slot = entry.getKey() == null ? -1 : entry.getKey();
            if (slot < 0 || slot >= size || PlayerStates.empty(entry.getValue())) continue;
            ItemStack marked = env.markTool(entry.getValue(), world.id());
            ItemStack received = inventory.getItem(slot);
            if (!PlayerStates.empty(received)) {
                int free = -1;
                for (int candidate = 0; candidate < Math.min(36, size); candidate++) {
                    if (!items.containsKey(candidate) && PlayerStates.empty(inventory.getItem(candidate))) { free = candidate;break; }
                }
                if (free < 0) continue; // Geschenk behalten; niemals in der geschützten Welt liegen lassen.
                inventory.setItem(free, received);
            }
            inventory.setItem(slot, marked);
        }
        inventory.setHeldItemSlot(0);
    }

    @Override
    public void regiveTools(Player player) {
        requireThread();
        ManagedWorldImpl world = visitWorld(player);
        if (world != null) giveTools(player, world);
    }

    /** Respawn-Ort für Besucher ({@code null} = nicht ändern); Werkzeuge kommen im nächsten Tick neu. */
    public Location respawn(Player player) {
        VisitImpl visit = visits.get(player.getUniqueId());
        ManagedWorldImpl world = visit == null ? null : worlds.byId(visit.worldId());
        if (world == null || world.world() == null) return null;
        env.nextTick(() -> { if (player.isOnline() && visits.get(player.getUniqueId()) == visit) giveTools(player, world); });
        Location[] result = new Location[1];
        hook(world, "respawn", hooks -> result[0] = hooks.respawn(player, visit));
        if (result[0] != null && world.world().equals(result[0].getWorld())) return result[0];
        return world.world().getSpawnLocation();
    }

    // ------------------------------------------------------------------ Regeln für Listener

    public boolean commandAllowed(Player player, String message) {
        ManagedWorldImpl world = visitWorld(player);
        if (world == null) return true;
        return CommandAllowList.allowed(message, world.spec().policy().allowedCommands(), env::commandName, env::sameCommand);
    }

    /** Admin-Ausnahme: Kreativ/Zuschauer, Recht, kein laufender Besuch. */
    public boolean bypass(Player player, ManagedWorldImpl world) {
        if (world == null || visits.containsKey(player.getUniqueId())) return false;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) return false;
        String permission = world.spec().adminPermission();
        return player.hasPermission("vystorm_core.worlds.bypass") || permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    /** Teleport erlaubt? Fremde Teleports in eine Plugin-Welt nur für deren Besucher (oder Admin-Ausnahme). */
    public boolean teleportAllowed(Player player, Location from, Location to) {
        if (ownTeleports.contains(player.getUniqueId()) || to == null) return true;
        ManagedWorldImpl target = worlds.byWorld(to.getWorld());
        if (target == null) return true;
        ManagedWorldImpl source = from == null ? null : worlds.byWorld(from.getWorld());
        if (target == source) return true;
        VisitImpl visit = visits.get(player.getUniqueId());
        if (visit != null && visit.worldId().equals(target.id())) return true;
        return bypass(player, target);
    }

    /** Nach einem Weltwechsel: fremder Teleport hinaus beendet den Besuch; Fremde in einer Plugin-Welt werden hinausgesetzt. */
    public void changedWorld(Player player, World from) {
        UUID id = player.getUniqueId();
        if (ownTeleports.contains(id)) return;
        VisitImpl visit = visits.get(id);
        ManagedWorldImpl source = worlds.byWorld(from);
        if (visit != null && source != null && source.id().equals(visit.worldId()) && visit.phase != Visit.Phase.LEAVING) {
            exit(player, ExitReason.WORLD_CHANGE);
            visit = null;
        }
        ManagedWorldImpl now = worlds.byWorld(player.getWorld());
        if (now != null && (visit == null || !visit.worldId().equals(now.id())) && !bypass(player, now)) {
            env.nextTick(() -> {
                if (player.isOnline() && worlds.byWorld(player.getWorld()) == now && !visits.containsKey(id)) teleportOwn(player, env.mainSpawn());
            });
        }
    }

    /** Portal/Endtransit in der Welt: Besuch regulär beenden (falls die Politik das will). */
    public void portal(Player player) {
        VisitImpl visit = visits.get(player.getUniqueId());
        ManagedWorldImpl world = visit == null ? null : worlds.byId(visit.worldId());
        if (world == null || visit.phase != Visit.Phase.ACTIVE || !world.spec().policy().portalExits()) return;
        env.nextTick(() -> { if (player.isOnline() && visits.get(player.getUniqueId()) == visit) exit(player, ExitReason.PORTAL); });
    }

    // ------------------------------------------------------------------ Sichtbarkeit

    @Override public void refreshVisibility(String worldId) { requireThread();visibility.refreshVisibility(worldId); }

    public void refreshAllVisibility() { requireThread();visibility.refreshAllVisibility(); }

    // ------------------------------------------------------------------ Ende

    /** Alle Besuche einer Welt synchron beenden (Neuaufbau, Entladen). */
    public void endAll(String worldId, ExitReason reason) {
        admissions.forEach((id, entry) -> { if (entry.world.id().equals(worldId)) cancelAdmission(id, entry, false); });
        for (VisitImpl visit : List.copyOf(visits.values())) {
            if (!visit.worldId().equals(worldId)) continue;
            Player player = env.player(visit.player());
            if (player != null && player.isOnline()) finishSync(player, visit, reason == ExitReason.REBUILD ? ExitReason.REBUILD : ExitReason.SHUTDOWN);
            else visits.remove(visit.player(), visit);
        }
    }

    /** Core-Stopp: alle Besuche synchron beenden. */
    public void shutdown() {
        stopped = true;
        admissions.values().forEach(entry -> entry.cancelled = true);
        snapshotWrites.shutdownNow();
        admissions.clear();
        for (VisitImpl visit : List.copyOf(visits.values())) {
            Player player = env.player(visit.player());
            if (player != null && player.isOnline()) finishSync(player, visit, ExitReason.SHUTDOWN);
            else visits.remove(visit.player(), visit);
        }
    }

    private void hook(ManagedWorldImpl world, String name, Consumer<VisitHooks> call) {
        try { call.accept(world.spec().hooks()); }
        catch (RuntimeException | LinkageError failure) { env.log("Hook " + name + " von " + world.id() + " fehlgeschlagen: " + failure); }
    }
}
