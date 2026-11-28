package Vystorm.vystorm_core.knownplayers.internal;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Localization;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Runs the known-player store on a server: reads {@code known-players.dat} off the server thread at start (or, without
 * one, seeds it once from {@code usercache.json}), records joins and quits, drops entries older than
 * {@code known-players.forget-after-days}, writes the file through {@link AtomicFiles} every 60 s when it changed and
 * once more on disable. A file that exists but cannot be read is never written over in that run. Core-internal.
 */
public final class KnownPlayersRuntime implements Listener {
    public static final String FILE = "known-players.dat";
    private static final long WRITE_TICKS = 1200L;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private static volatile KnownPlayersRuntime current;

    private final Plugin core;
    private final KnownPlayerStore store = new KnownPlayerStore();
    private final Path file;
    private final int forgetAfterDays;
    private final Object writeLock = new Object();
    private volatile boolean loaded;
    private volatile boolean readOnly;
    private int task = -1;
    private boolean writeFailedLogged;
    /** Snapshot numbers (server thread) and the newest one written (under writeLock): an older snapshot never wins. */
    private long snapshots;
    private long written;

    private KnownPlayersRuntime(Plugin core, Path file, int forgetAfterDays) {
        this.core = core;
        this.file = file;
        this.forgetAfterDays = forgetAfterDays;
    }

    /** The running store, or null (off or not started). */
    public static KnownPlayerStore store() {
        KnownPlayersRuntime r = current;
        return r == null ? TESTING : r.store;
    }

    public static boolean loaded() {
        KnownPlayersRuntime r = current;
        return r == null ? TESTING != null : r.loaded;
    }

    /** Only tests: a store without a server (null to remove). */
    public static volatile KnownPlayerStore TESTING;

    public static synchronized void start(Plugin core) {
        if (current != null) return;
        if (!core.getConfig().getBoolean("known-players.enabled", true)) {
            DebugMessages.mid("[KnownPlayers] Switched off (known-players.enabled: false)");
            return;
        }
        KnownPlayersRuntime r = new KnownPlayersRuntime(core, core.getDataFolder().toPath().resolve(FILE),
                core.getConfig().getInt("known-players.forget-after-days", 365));
        current = r;
        for (Player online : Bukkit.getOnlinePlayers()) r.join(online);   // /reload: players already in
        Bukkit.getPluginManager().registerEvents(r, core);
        Bukkit.getScheduler().runTaskAsynchronously(core, r::load);
        r.task = Bukkit.getScheduler().runTaskTimer(core, r::saveLater, WRITE_TICKS, WRITE_TICKS).getTaskId();
        KnownPlayersSettings.register(core);
    }

    public static synchronized void shutdown() {
        KnownPlayersRuntime r = current;
        if (r == null) return;
        current = null;
        HandlerList.unregisterAll(r);
        if (r.task != -1) {
            try { Bukkit.getScheduler().cancelTask(r.task); } catch (RuntimeException ignored) { }
        }
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) r.store.quit(online.getUniqueId(), now);
        if (r.loaded && !r.readOnly && r.store.takeDirty()) r.write(r.store.all(), ++r.snapshots);
    }

    // ------------------------------------------------------------------ load

    private void load() {
        Loaded result;
        try {
            result = read(file, Bukkit.getWorldContainer().toPath().resolve("usercache.json"), System.currentTimeMillis());
        } catch (IOException unreadable) {
            core.getLogger().log(Level.SEVERE, "Known players: " + FILE + " could not be read; it is kept as it is and not written in this run", unreadable);
            Bukkit.getScheduler().runTask(core, () -> { readOnly = true; loaded = true; });
            return;
        }
        Bukkit.getScheduler().runTask(core, () -> apply(result));
    }

    /** What the loader read: entries, damaged lines, whether they came from usercache.json. */
    record Loaded(List<KnownPlayerStore.Entry> entries, int damaged, boolean seeded) {}

    static Loaded read(Path file, Path userCache, long now) throws IOException {
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // not our format, or nothing readable in it: keep the file as it is and do not write over it
            if (!text.isBlank() && !text.startsWith(KnownPlayerCodec.HEADER)) throw new IOException("no " + KnownPlayerCodec.HEADER + " header");
            KnownPlayerCodec.Decoded d = KnownPlayerCodec.decode(text);
            if (d.entries().isEmpty() && d.damaged() > 0) throw new IOException(d.damaged() + " damaged lines and no readable entry");
            return new Loaded(d.entries(), d.damaged(), false);
        }
        if (!Files.isRegularFile(userCache)) return new Loaded(List.of(), 0, true);
        String json;
        try {
            json = Files.readString(userCache, StandardCharsets.UTF_8);
        } catch (IOException cacheUnreadable) {
            return new Loaded(List.of(), 1, true);
        }
        UserCacheSeed.Seed seed = UserCacheSeed.parse(json, now);
        return new Loaded(seed.entries(), seed.skipped(), true);
    }

    private void apply(Loaded result) {
        if (current != this) return;
        for (KnownPlayerStore.Entry e : result.entries()) store.merge(e);
        int dropped = forgetAfterDays > 0 ? store.retain(System.currentTimeMillis() - forgetAfterDays * DAY_MS) : 0;
        loaded = true;
        if (result.seeded()) store.markDirty();
        String from = result.seeded() ? "usercache.json (first start)" : FILE;
        core.getLogger().info("Known players: " + store.size() + " (" + result.entries().size() + " read from " + from
                + (result.damaged() > 0 ? ", " + result.damaged() + " damaged or skipped entries" : "")
                + (dropped > 0 ? ", " + dropped + " not seen for " + forgetAfterDays + " days dropped" : "") + ")");
    }

    // ------------------------------------------------------------------ join, quit

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { join(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { store.quit(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    private void join(Player player) {
        try {
            store.joined(player.getUniqueId(), player.getName(), System.currentTimeMillis(), skin(player));
        } catch (RuntimeException odd) {
            DebugMessages.low("[KnownPlayers] Join of " + player.getName() + " not recorded: " + odd);
        }
    }

    /** The skin address the server already has with the online profile (Bukkit API since 1.18.1); no lookup. */
    static String skin(Player player) {
        try {
            org.bukkit.profile.PlayerProfile profile = Vystorm.vystorm_core.compat.PlatformCompat.getPlayerProfile(player);
            java.net.URL url = profile == null ? null : profile.getTextures().getSkin();
            return url == null ? null : url.toString();
        } catch (RuntimeException | LinkageError none) {
            return null;
        }
    }

    // ------------------------------------------------------------------ write

    private void saveLater() {
        if (!loaded || readOnly || !store.takeDirty()) return;
        List<KnownPlayerStore.Entry> all = store.all();
        long seq = ++snapshots;
        Bukkit.getScheduler().runTaskAsynchronously(core, () -> write(all, seq));
    }

    private void write(List<KnownPlayerStore.Entry> all, long seq) {
        synchronized (writeLock) {
            if (seq <= written) return;   // a newer snapshot is already on disk
            try {
                AtomicFiles.write(file, KnownPlayerCodec.encode(all).getBytes(StandardCharsets.UTF_8));
                written = seq;
                writeFailedLogged = false;
            } catch (IOException | RuntimeException failure) {
                store.markDirty();
                if (!writeFailedLogged) core.getLogger().log(Level.WARNING, "Known players: " + FILE + " not written; trying again in a minute", failure);
                writeFailedLogged = true;
            }
        }
    }

    /** For {@code /vadmin players}: Core's language bundle. */
    static Vystorm.vystorm_core.lang.Lang lang() { return Localization.get().core(); }

    /** Whether the running store is ours (admin texts). */
    public static boolean running() { return current != null && KnownPlayers.enabled(); }
}
