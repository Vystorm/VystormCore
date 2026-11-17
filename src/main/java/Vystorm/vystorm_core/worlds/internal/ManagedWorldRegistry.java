package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.ExitReason;
import Vystorm.vystorm_core.worlds.ManagedWorldException;
import Vystorm.vystorm_core.worlds.ManagedWorldException.Reason;
import Vystorm.vystorm_core.worlds.WorldSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Lebenszyklus der Plugin-Welten: laden (mit Hash-Prüfung), neu aufbauen, entladen. Nur Server-Thread; Abfragen
 * ({@link WorldLookup}) sind threadsicher.
 */
public final class ManagedWorldRegistry implements WorldLookup {
    /** Bukkit-Seite (im Test ersetzt). */
    public interface Host {
        boolean primaryThread();
        default boolean stopping() { return false; }
        /** Schon geladene Welt dieses Namens oder {@code null}. */
        World loaded(String name);
        /** Vermuteter Ordner vor dem ersten Laden (Paper 26: {@code <level>/dimensions/minecraft/<name>}). */
        Path defaultFolder(String worldName);
        /** Authoritative destination, resolved without loading or touching the world. */
        default Path folderFor(WorldSpec spec) { return defaultFolder(spec.worldName()); }
        World create(WorldSpec spec);
        boolean unload(World world, boolean save);
        Path folderOf(World world);
        /** Spielregeln, Zeit, Wetter, Grenze, Spawn, Sichtweiten, Autosave, Schwierigkeit. */
        void configure(World world, WorldSpec spec);
        /** Region, Weltprofil, Generator-Name in Core anmelden (Fehler nur loggen). */
        void attach(Plugin owner, World world, WorldSpec spec);
        void detach(World world, WorldSpec spec);
        /** Spieler, die noch in der Welt sind, zum Hauptspawn. */
        void evacuate(World world);
        void log(String message);
    }

    private final Host host;
    private final WorldsWarnings warnings;
    private final Map<String, ManagedWorldImpl> byId = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedWorldImpl> byUid = new ConcurrentHashMap<>();
    private BiConsumer<String, ExitReason> endVisits = (id, reason) -> {};

    public ManagedWorldRegistry(Host host, Path pathsDir) {
        this.host = host;
        this.warnings = new WorldsWarnings(host::log);
    }

    /** Wird vom Besuchsdienst gesetzt: beendet alle Besuche einer Welt (synchron). */
    public void endVisitsWith(BiConsumer<String, ExitReason> value) { endVisits = value; }

    @Override public ManagedWorldImpl byId(String id) { return id == null ? null : byId.get(id); }

    @Override public ManagedWorldImpl byWorld(World world) { return world == null ? null : byUid.get(world.getUID()); }

    @Override public Collection<ManagedWorldImpl> all() { return List.copyOf(byId.values()); }

    private void requireThread() {
        if (!host.primaryThread()) throw new ManagedWorldException(Reason.ILLEGAL_CALL, "managed worlds need the server thread");
    }

    public ManagedWorldImpl register(Plugin owner, WorldSpec spec) {
        requireThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spec, "spec");
        if (byId.containsKey(spec.id())) throw new ManagedWorldException(Reason.ID_TAKEN, "world id already registered: " + spec.id());
        if (host.loaded(spec.worldName()) != null)
            throw new ManagedWorldException(Reason.ALREADY_LOADED, "world " + spec.worldName() + " is already loaded and not managed – refusing to touch it");
        Path guess = host.folderFor(spec).toAbsolutePath().normalize();
        boolean fresh = prepare(guess, spec);
        World world = host.create(spec);
        if (world == null) throw new ManagedWorldException(Reason.BUKKIT_FAILED, "Bukkit did not create " + spec.worldName());
        Path real = host.folderOf(world).toAbsolutePath().normalize();
        if (!real.equals(guess)) {
            if (!host.unload(world, false)) {
                host.log("World " + spec.worldName() + " could not be unloaded after an unexpected path");
            }
            throw new ManagedWorldException(Reason.BUKKIT_FAILED,
                    "world host returned an unexpected path after preflight: " + real + " (expected " + guess + ")");
        }
        try {
            WorldFolders.writeMarker(real, new WorldFolders.Marker(spec.id(), owner.getName(), spec.layoutHash(), false));
        } catch (IOException failure) {
            host.log("Marker for " + spec.id() + " not written (the next start rebuilds): " + failure);
        }
        host.configure(world, spec);
        host.attach(owner, world, spec);
        ManagedWorldImpl managed = new ManagedWorldImpl(owner, spec, world, real, fresh);
        byId.put(spec.id(), managed);
        byUid.put(world.getUID(), managed);
        host.log("Plugin-Welt " + spec.id() + " geladen (" + real + ", Hash " + abbreviate(spec.layoutHash()) + (fresh ? ", neu aufgebaut" : "") + ")");
        return managed;
    }

    /** Prüft den (vermuteten) Ordner vor dem Laden; löscht bei abweichendem Hash. Liefert „neu aufgebaut“. */
    private boolean prepare(Path folder, WorldSpec spec) {
        if (!Files.isDirectory(folder)) return true;
        Optional<WorldFolders.Marker> marker = WorldFolders.readMarker(folder, spec.id(), spec.legacyHashFile());
        WorldFolders.Decision decision = WorldFolders.decide(WorldFolders.hasData(folder), marker.orElse(null), spec.id(), spec.layoutHash());
        switch (decision) {
            case REFUSE_FOREIGN -> throw new ManagedWorldException(Reason.FOREIGN_FOLDER,
                    folder + " has chunk data without a matching " + WorldFolders.MARKER + " – refusing to touch it");
            case WIPE_AND_LOAD -> {
                host.log("Layout of " + spec.id() + " changed (" + abbreviate(marker.map(WorldFolders.Marker::hash).orElse("")) + " → "
                        + abbreviate(spec.layoutHash()) + "): world is rebuilt (" + folder + ")");
                wipeOrThrow(folder, spec);
                return true;
            }
            default -> { return marker.isEmpty(); }
        }
    }

    private void wipeOrThrow(Path folder, WorldSpec spec) {
        WorldFolders.Marker marker = WorldFolders.readMarker(folder, spec.id(), spec.legacyHashFile()).orElse(null);
        if (marker == null || !spec.id().equals(marker.id()))
            throw new ManagedWorldException(Reason.FOREIGN_FOLDER, "ownership marker missing or changed before wipe: " + folder);
        List<String> failures = WorldFolders.wipe(folder);
        if (failures.isEmpty()) return;
        failures.stream().limit(20).forEach(f -> host.log("Not deleted: " + f));
        throw new ManagedWorldException(Reason.WIPE_FAILED, failures.size() + " files of " + spec.id() + " could not be deleted – world not loaded");
    }

    /** Beendet alle Besuche ({@link ExitReason#REBUILD}), entlädt, löscht die Daten und lädt neu. */
    public ManagedWorldImpl rebuild(Plugin owner, WorldSpec spec) {
        requireThread();
        if (host.stopping()) throw new ManagedWorldException(Reason.ILLEGAL_CALL, "cannot rebuild while the server is stopping");
        ManagedWorldImpl old = byId.get(spec.id());
        if (old == null) return register(owner, spec);
        if (!old.owner().equals(owner)) throw new ManagedWorldException(Reason.ILLEGAL_CALL, spec.id() + " belongs to " + old.owner().getName());
        WorldFolders.Marker marker = WorldFolders.readMarker(old.folder(), old.id(), old.spec().legacyHashFile()).orElse(null);
        if (marker == null || !old.id().equals(marker.id()))
            throw new ManagedWorldException(Reason.FOREIGN_FOLDER, "ownership marker missing or changed: " + old.folder());
        unload(old, ExitReason.REBUILD);
        wipeOrThrow(old.folder(), spec);
        try { Files.deleteIfExists(old.folder().resolve(WorldFolders.MARKER)); }
        catch (IOException failure) { warnings.warn("marker-delete", "Marker of " + old.id() + " not deleted: " + failure); }
        return register(owner, spec);
    }

    /**
     * Übernimmt geänderte Einstellungen (Regeln, Zeit, Region, Profil, Sichtbarkeit, Besuchsregeln, Hooks) ohne
     * Neuaufbau. Nur bei gleichem Weltnamen und gleichem Layout-Hash; sonst {@link #rebuild}.
     */
    public ManagedWorldImpl reconfigure(Plugin owner, WorldSpec spec) {
        requireThread();
        ManagedWorldImpl old = byId.get(spec.id());
        if (old == null || old.world() == null) throw new ManagedWorldException(Reason.ILLEGAL_CALL, spec.id() + " is not loaded");
        if (!old.owner().equals(owner)) throw new ManagedWorldException(Reason.ILLEGAL_CALL, spec.id() + " belongs to " + old.owner().getName());
        if (!old.spec().worldName().equals(spec.worldName()) || !old.spec().layoutHash().equals(spec.layoutHash()))
            throw new ManagedWorldException(Reason.ILLEGAL_CALL, "world name or layout hash changed – use rebuild");
        World world = old.world();
        host.detach(world, old.spec());
        host.configure(world, spec);
        host.attach(owner, world, spec);
        ManagedWorldImpl next = new ManagedWorldImpl(owner, spec, world, old.folder(), old.rebuilt());
        byId.put(spec.id(), next);
        byUid.put(world.getUID(), next);
        return next;
    }

    public boolean unregister(Plugin owner, String id) {
        requireThread();
        ManagedWorldImpl managed = byId.get(id);
        if (managed == null) return false;
        if (owner != null && !managed.owner().equals(owner)) throw new ManagedWorldException(Reason.ILLEGAL_CALL, id + " belongs to " + managed.owner().getName());
        unload(managed, ExitReason.SHUTDOWN);
        return true;
    }

    private void unload(ManagedWorldImpl managed, ExitReason reason) {
        endVisits.accept(managed.id(), reason);
        World world = managed.world();
        if (world == null) { byId.remove(managed.id(), managed);return; }
        if (!host.stopping()) host.evacuate(world);
        if (!host.stopping() && !host.unload(world, reason != ExitReason.REBUILD && managed.spec().autoSave())) {
            host.log("World " + managed.spec().worldName() + " could not be unloaded");
            throw new ManagedWorldException(Reason.BUKKIT_FAILED, "could not unload " + managed.spec().worldName());
        }
        host.detach(world, managed.spec());
        byId.remove(managed.id(), managed);
        byUid.remove(world.getUID(), managed);
        managed.unloaded();
    }

    /** Alle Welten eines Plugins entladen (Plugin-Disable, {@code CoreLifecycle}). */
    public void release(Plugin owner) {
        for (ManagedWorldImpl managed : all()) {
            if (!managed.owner().equals(owner)) continue;
            try { unregister(owner, managed.id()); } catch (RuntimeException failure) { host.log("Entladen von " + managed.id() + " fehlgeschlagen: " + failure); }
        }
    }

    /** Core-Stopp: alle Welten entladen. */
    public void shutdown() {
        for (ManagedWorldImpl managed : all()) {
            try { unregister(null, managed.id()); } catch (RuntimeException failure) { host.log("Entladen von " + managed.id() + " fehlgeschlagen: " + failure); }
        }
    }

    static String abbreviate(String hash) { return hash == null || hash.length() <= 12 ? String.valueOf(hash) : hash.substring(0, 12); }
}
