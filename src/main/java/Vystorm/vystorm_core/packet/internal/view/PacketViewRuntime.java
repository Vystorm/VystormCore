package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.packet.view.ChunkLayer;
import Vystorm.vystorm_core.packet.view.LayerSpec;
import Vystorm.vystorm_core.packet.view.ViewChunks;
import Vystorm.vystorm_core.packet.view.ViewLayer;
import Vystorm.vystorm_core.packet.view.ViewStats;
import Vystorm.vystorm_core.packet.view.ViewerRef;
import Vystorm.vystorm_core.packet.view.WireStates;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The packet view on a running server: the self-test (once, on the first {@code supported()} call), the layers, the
 * viewers and their handler pairs. Handlers are in a connection only while at least one layer is registered: the first
 * layer attaches a pair to every online player and every player who joins, the last one to leave takes them all out
 * again. Server thread for everything here unless a method says otherwise.
 *
 * <p>Internal: not part of the public API.
 */
public final class PacketViewRuntime implements Listener {
    private static volatile PacketViewRuntime instance;

    private Plugin core;
    private Logger log = Logger.getLogger("Vystorm_Core");
    private boolean checked;
    private volatile String unsupported = "not checked yet";
    private String summary = "";
    private ViewNms nms;
    private ViewEngine engine;
    private ViewRouter router;
    private ViewSender sender;
    private WireStates wireStates;
    final ViewCounters stats = new ViewCounters();
    private final LayerRegistry layers = new LayerRegistry(this::closing);
    private final Map<UUID, ViewerSlot> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerSlot> virtual = new ConcurrentHashMap<>();
    private final BitSet slots = new BitSet();
    private BukkitTask ticker;
    private long lastRecheck;

    public static synchronized PacketViewRuntime get() {
        if (instance == null) instance = new PacketViewRuntime();
        return instance;
    }

    /** Core's enable: only the listener (nothing is attached, nothing is tested until a plugin asks). */
    public void start(Plugin core) {
        this.core = core;
        this.log = core.getLogger();
        Bukkit.getPluginManager().registerEvents(this, core);
    }

    /** Core's disable: every pair leaves (held packets go out as the layers decide now), then the layers are dropped. */
    public static void shutdownInstance() {
        PacketViewRuntime rt = instance;
        if (rt == null) return;
        // the layers hear it first (a layer whose plugin keeps running applies its own off rule), then every pair leaves
        // while the layers still judge what it holds, and only then are the layers dropped
        for (LayerRegistry.Entry e : rt.layers.all()) {
            try { e.layer.stopped("Vystorm Core stops"); } catch (RuntimeException | LinkageError ignored) { }
        }
        try { rt.onEveryLoop(ViewTap::detachNow); } catch (RuntimeException | LinkageError ignored) { }
        try { rt.detachAll(); } catch (RuntimeException | LinkageError ignored) { }
        for (LayerRegistry.Entry e : rt.layers.all()) rt.layers.remove(e);
        if (rt.ticker != null) { rt.ticker.cancel(); rt.ticker = null; }
        HandlerList.unregisterAll(rt);
        instance = null;
    }

    // ---- support -------------------------------------------------------------------------------------------------

    /** Whether the view runs here; the first call on the server thread runs the self-test. */
    public boolean supported() {
        if (!checked) {
            if (!Bukkit.isPrimaryThread()) return false;
            check();
        }
        return unsupported == null;
    }

    public Optional<String> unsupportedReason() {
        if (!checked && Bukkit.isPrimaryThread()) check();
        return Optional.ofNullable(unsupported);
    }

    public String summary() { return summary; }

    private void check() {
        checked = true;
        String platform = ViewPlatform.check();
        if (platform != null) { unsupported = platform; return; }
        try {
            nms = ViewNms.load();
            World first = Bukkit.getWorlds().get(0);
            Location spawn = first.getSpawnLocation();
            int cx = spawn.getBlockX() >> 4, cz = spawn.getBlockZ() >> 4;
            first.getChunkAt(cx, cz);
            var tables = nms.tables();
            if (core != null) ViewSelfTest.dumpDir = core.getDataFolder().toPath().resolve("packet-view");
            ViewSelfTest.Result st = ViewSelfTest.run(nms, tables, first, cx, cz, fill(first));
            summary = st.summary();
            engine = new ViewEngine(tables, stats, st.bitsetBytes());
            engine.warnings = line -> log.warning(line);
            engine.onBreak(why -> Bukkit.getScheduler().runTask(core, () -> brokeDown(why)));
            boolean[] air = new boolean[tables.size()], fluid = new boolean[tables.size()];
            for (int i = 0; i < air.length; i++) { air[i] = tables.air(i); fluid[i] = tables.fluid(i); }
            wireStates = new WireStates(air, fluid);
            router = new ViewRouter(nms, engine, layers);
            sender = new ViewSender(nms, stats);
            for (World w : Bukkit.getWorlds()) addWorld(w);
            unsupported = null;
            log.info("Packet view self-test " + summary);
        } catch (Throwable failure) {
            unsupported = "the self-test with a real chunk failed (" + failure + ")";
            summary = "FAILED: " + failure;
            log.log(Level.WARNING, "Packet view: " + unsupported + "; layers cannot be registered on this server.", failure);
        }
    }

    /** Fill states of the self-test's checker mask (stone, deepslate below y 0). */
    private int[] fill(World w) {
        int minY = w.getMinHeight(), sections = (w.getMaxHeight() - minY) >> 4;
        int stone = nms.defaultId("STONE"), deep = nms.defaultId("DEEPSLATE");
        int[] f = new int[sections];
        for (int s = 0; s < sections; s++) f[s] = minY + s * 16 < 0 ? deep : stone;
        return f;
    }

    private void addWorld(World w) {
        int minY = w.getMinHeight(), sections = (w.getMaxHeight() - minY) >> 4;
        Object key = null;
        try { key = nms.dimension(w); }
        catch (RuntimeException e) { log.warning("Packet view: the dimension of world " + w.getName() + " is not known (" + e + "); a player who enters it is followed by his position instead"); }
        engine.addWorld(key, w.getUID(), minY, sections);
    }

    /** The self-test failed twice after a packet could not be read: the view stays off for this server. */
    private void brokeDown(String why) {
        unsupported = "switched itself off: " + why;
        log.warning("Packet view is OFF: " + why + "; packets pass unchanged.");
        for (LayerRegistry.Entry e : layers.all()) {
            try { e.layer.stopped(why); } catch (RuntimeException | LinkageError failure) { log.warning("Packet view: layer " + e.spec.id() + " failed in stopped: " + failure); }
        }
        detachAll();
    }

    // ---- layers --------------------------------------------------------------------------------------------------

    public ViewLayer register(Plugin owner, LayerSpec spec, ChunkLayer layer) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("register on the server thread");
        if (owner == null || !owner.isEnabled()) throw new IllegalStateException("the owner plugin is not enabled");
        if (spec == null || layer == null) throw new NullPointerException("spec and layer are needed");
        if (spec.kind() == LayerSpec.Kind.CONTENT)
            throw new UnsupportedOperationException("CONTENT layers come with Core 0.74.0 (capability packet-view-content); this Core runs MASK layers only");
        if (!supported()) throw new IllegalStateException("the packet view does not run on this server: " + unsupported);
        boolean first = layers.isEmpty();
        LayerRegistry.Entry e = layers.add(spec, owner, layer);
        if (spec.collision()) log.warning("Packet view: layer " + spec.id() + " of " + owner.getName() + " changes what players can stand on or walk into (collision); anti-cheat plugins may flag such players.");
        log.info("Packet view: layer " + spec.id() + " (" + spec.kind() + ", " + spec.scope() + ", priority " + spec.priority() + ") registered by " + owner.getName() + ".");
        if (first) {
            attachAll();
            if (ticker == null) ticker = Bukkit.getScheduler().runTaskTimer(core, this::tick, 1, 1);
        }
        return e;
    }

    /** Closes every layer of a plugin (also when it disables). */
    public void release(Plugin owner) { for (LayerRegistry.Entry e : layers.of(owner)) e.close(); }

    public List<ViewLayer> layers() { return List.of(layers.all()); }

    /**
     * A layer closes: chunks held in the connections go out first while the layer still judges them (as at the end of
     * their hold time), then it leaves the chain; the last layer takes every pair out.
     */
    private void closing(LayerRegistry.Entry e) {
        LayerRegistry.Entry[] all = layers.all();
        boolean last = all.length == 1 && all[0] == e;
        // on every connection's event loop, while the layer is still in the chain: what the pair holds goes out judged by
        // it (the last layer takes the pairs out in the same task, so nothing held can pass after it is gone)
        onEveryLoop(last ? ViewTap::detachNow : ViewTap::flushNow);
        if (!layers.remove(e)) return;
        log.info("Packet view: layer " + e.spec.id() + " of " + (e.owner == null ? "?" : e.owner.getName()) + " removed.");
        if (layers.isEmpty()) {
            detachAll();
            if (ticker != null) { ticker.cancel(); ticker = null; }
        }
    }

    /** Runs a task on the event loop of every attached connection and waits for all of them (at most a second; server thread). */
    private void onEveryLoop(java.util.function.Consumer<Channel> task) {
        List<Channel> channels = new ArrayList<>();
        for (ViewerSlot v : viewers.values()) {
            Channel ch = channel(v);
            if (ch != null && v.attached) channels.add(ch);
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(channels.size());
        for (Channel ch : channels) {
            try { ch.eventLoop().execute(() -> { try { task.accept(ch); } catch (RuntimeException | LinkageError ignored) { } finally { latch.countDown(); } }); }
            catch (RuntimeException rejected) { latch.countDown(); }
        }
        try { if (!latch.await(1, java.util.concurrent.TimeUnit.SECONDS)) log.warning("Packet view: " + latch.getCount() + " connections did not answer within a second while a layer closed."); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    // ---- viewers ---------------------------------------------------------------------------------------------------

    private Channel channel(ViewerSlot v) {
        if (v.player == null || nms == null) return null;
        try { return nms.channel(v.player); } catch (RuntimeException gone) { return null; }
    }

    private ViewerRef newRef(UUID id, String name) {
        synchronized (slots) {
            int s = slots.nextClearBit(0);
            slots.set(s);
            return new ViewerRef(id, name, s);
        }
    }

    private void freeRef(ViewerRef ref) { synchronized (slots) { slots.clear(ref.slot()); } }

    private void attachAll() { for (Player p : Bukkit.getOnlinePlayers()) attach(p, 0); }

    private void detachAll() {
        for (ViewerSlot v : new ArrayList<>(viewers.values())) {
            Channel ch = channel(v);
            if (ch != null) try { ViewTap.detach(ch); } catch (RuntimeException ignored) { }
            v.attached = false;
            leave(v);
        }
        viewers.clear();
    }

    private void leave(ViewerSlot v) {
        for (LayerRegistry.Entry e : layers.all()) {
            try { e.layer.left(v.ref); } catch (RuntimeException | LinkageError failure) { log.warning("Packet view: layer " + e.spec.id() + " failed in left: " + failure); }
        }
        freeRef(v.ref);
    }

    /** A new viewer and a new pair in this player's connection (replacing one already there). */
    private void attach(Player p, int attempt) {
        if (router == null || engine.broken() != null) return;
        World w = p.getWorld();
        ViewerSlot old = viewers.get(p.getUniqueId());
        ViewerSlot v = old != null && attempt > 0 ? old : new ViewerSlot(newRef(p.getUniqueId(), p.getName()), p, w.getUID(), w.getMinHeight(), (w.getMaxHeight() - w.getMinHeight()) >> 4, packet -> nms.send(p, packet));
        if (old != null && old != v) leave(old);
        viewers.put(p.getUniqueId(), v);
        v.attached = true;
        Consumer<String> failed = why -> Bukkit.getScheduler().runTask(core, () -> notAttached(p, v, why, attempt));
        try {
            Channel ch = nms.channel(p);
            String why = ViewTap.attach(router, engine, v, ch, failed);
            if (why != null) failed.accept(why);
            else if (attempt > 0) sender.resend(v, w, square(p, Bukkit.getViewDistance())); // the client got real chunks meanwhile
        } catch (RuntimeException e) { failed.accept(e.toString()); }
    }

    private void notAttached(Player p, ViewerSlot v, String why, int attempt) {
        v.attached = false;
        if (!p.isOnline() || viewers.get(p.getUniqueId()) != v || layers.isEmpty()) return;
        if (attempt < 3) {
            log.warning("Packet view cannot reach the connection of " + p.getName() + " yet (" + why + "); trying again in a second.");
            Bukkit.getScheduler().runTaskLater(core, () -> { if (p.isOnline() && viewers.get(p.getUniqueId()) == v && !layers.isEmpty()) attach(p, attempt + 1); }, 20);
            return;
        }
        log.warning("Packet view cannot reach the connection of " + p.getName() + " (" + why + ").");
        for (LayerRegistry.Entry e : layers.all()) {
            try { e.layer.notAttached(v.ref, p, why); } catch (RuntimeException | LinkageError failure) { log.warning("Packet view: layer " + e.spec.id() + " failed in notAttached: " + failure); }
        }
    }

    static List<Long> square(Player p, int viewDistance) {
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        List<Long> out = new ArrayList<>();
        for (int dx = -viewDistance; dx <= viewDistance; dx++)
            for (int dz = -viewDistance; dz <= viewDistance; dz++) out.add(ViewChunks.key(pcx + dx, pcz + dz));
        return out;
    }

    // MONITOR: after the plugins' own join handlers (a layer's per-viewer state exists, and a foreign pair that went in
    // at join sits outside Core's pair after the repair queued here)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (layers.isEmpty() || router == null) return;
        attach(e.getPlayer(), 0);
        try { ViewTap.repairLater(nms.channel(e.getPlayer())); } catch (RuntimeException ignored) { }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        ViewerSlot v = viewers.remove(e.getPlayer().getUniqueId());
        if (v != null) leave(v);
    }

    @EventHandler public void onWorldLoad(WorldLoadEvent e) { if (engine != null) addWorld(e.getWorld()); }

    @EventHandler public void onWorldUnload(WorldUnloadEvent e) { if (engine != null) engine.removeWorld(e.getWorld().getUID()); }

    // ---- ticks -------------------------------------------------------------------------------------------------------

    private void tick() {
        if (engine == null || engine.broken() != null) return;
        // a packet could not be read: the self-test decides whether this server's format differs (then the view goes
        // off) or the failure belonged to that packet (it was not sent; nothing else changes)
        if (engine.recheck.get() && System.currentTimeMillis() - lastRecheck > 60_000) {
            engine.recheck.set(false);
            lastRecheck = System.currentTimeMillis();
            recheck();
            if (engine.broken() != null) return;
        }
        // chunks that were not sent because they could not be rewritten: asked again (at most twice per chunk)
        int r = 0;
        for (Iterator<ViewEngine.Retry> it = engine.retries.iterator(); it.hasNext() && r < 8; r++) {
            ViewEngine.Retry e = it.next();
            it.remove();
            ViewerSlot v = viewers.get(e.viewer()); // virtual viewers of proofs are not asked again (as in AntiXray)
            World w = Bukkit.getWorld(e.world());
            if (v == null || v.player == null || w == null || !v.player.isOnline() || !w.equals(v.player.getWorld())) continue;
            Object lc = nms.chunkNow(nms.level(w), ViewChunks.x(e.chunk()), ViewChunks.z(e.chunk()));
            if (lc == null) continue;
            v.sink.accept(nms.chunkPacket(nms.level(w), lc));
            stats.inc("retry.chunks");
        }
    }

    /** Runs the self-test again on a loaded chunk next to a player (or the spawn); switches the view off only if it fails twice. */
    private void recheck() {
        World w = null;
        int cx = 0, cz = 0;
        for (ViewerSlot v : viewers.values()) {
            Player p = v.player;
            if (p == null || !p.isOnline()) continue;
            Location l = p.getLocation();
            if (!p.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            w = p.getWorld(); cx = l.getBlockX() >> 4; cz = l.getBlockZ() >> 4;
            break;
        }
        if (w == null) {
            w = Bukkit.getWorlds().get(0);
            Location s = w.getSpawnLocation();
            cx = s.getBlockX() >> 4; cz = s.getBlockZ() >> 4;
            w.getChunkAt(cx, cz);
        }
        Throwable first;
        try {
            ViewSelfTest.Result res = ViewSelfTest.run(nms, engine.tables, w, cx, cz, fill(w), engine.bitsetBytes);
            log.info("Packet view: a packet could not be read, so the self-test ran again and passes (" + res.summary() + "): the failure belonged to that packet, which was not sent.");
            return;
        } catch (Throwable failure) { first = failure; }
        try {
            World s = Bukkit.getWorlds().get(0);
            Location sp = s.getSpawnLocation();
            s.getChunkAt(sp.getBlockX() >> 4, sp.getBlockZ() >> 4);
            ViewSelfTest.Result res = ViewSelfTest.run(nms, engine.tables, s, sp.getBlockX() >> 4, sp.getBlockZ() >> 4, fill(s), engine.bitsetBytes);
            log.info("Packet view: a packet could not be read; the self-test failed once (" + first + ") but passes on the spawn chunk (" + res.summary() + ").");
        } catch (Throwable failure) {
            engine.breakdown("the self-test failed twice when it ran again after a packet could not be read (" + first + "; " + failure + ")");
        }
    }

    // ---- queries and sends ---------------------------------------------------------------------------------------

    public Optional<ViewerRef> viewer(Player p) {
        ViewerSlot v = viewers.get(p.getUniqueId());
        return v == null ? Optional.empty() : Optional.of(v.ref);
    }

    public Set<Long> heldChunks(Player p) {
        ViewerSlot v = viewers.get(p.getUniqueId());
        return v == null ? Set.of() : Set.copyOf(v.state.layer().chunks());
    }

    public Collection<Player> holders(World w, int cx, int cz) {
        List<Player> out = new ArrayList<>();
        for (ViewerSlot v : viewers.values()) {
            ClientWorldState.Layer l = v.state.layer();
            if (v.player != null && w.getUID().equals(l.world()) && l.holds(cx, cz)) out.add(v.player);
        }
        return out;
    }

    public int resend(Player p, Collection<Long> keys) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("resend on the server thread");
        ViewerSlot v = viewers.get(p.getUniqueId());
        if (v == null || sender == null) return 0;
        return sender.resend(v, p.getWorld(), keys);
    }

    public int sendReal(Player p, World w, ChunkCells cells, boolean unmasked) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("sendReal on the server thread");
        ViewerSlot v = viewers.get(p.getUniqueId());
        if (v == null || sender == null || !w.getUID().equals(v.state.world())) return 0;
        return sender.sendReal(v, w, cells, unmasked);
    }

    public int wireId(BlockData data) {
        if (nms == null && !supported()) throw new IllegalStateException("the packet view does not run on this server: " + unsupported);
        return nms.wireId(data);
    }

    public WireStates wireStates() {
        if (wireStates == null && !supported()) throw new IllegalStateException("the packet view does not run on this server: " + unsupported);
        return wireStates;
    }

    public ViewStats stats() {
        List<ViewStats.Layer> rows = new ArrayList<>();
        for (LayerRegistry.Entry e : layers.all())
            rows.add(new ViewStats.Layer(e.spec.id(), e.owner == null ? "?" : e.owner.getName(), e.spec.kind(), e.spec.priority(), e.spec.collision(),
                    e.chunks.sum(), e.cells.sum(), e.dropped.sum(), e.errors.sum(), e.holds.sum(),
                    e.applyNanos.percentile(.5) / 1e3, e.applyNanos.percentile(.95) / 1e3, e.applyNanos.percentile(.99) / 1e3));
        int attached = 0;
        for (ViewerSlot v : viewers.values()) if (v.attached) attached++;
        Map<String, Long> counters = new LinkedHashMap<>(stats.counters());
        return new ViewStats(unsupported == null && checked, unsupported == null ? summary : unsupported, viewers.size() + virtual.size(), attached, rows, counters);
    }

    /** The samples of the router (chunk nanos, hold millis) for {@code /vadmin packets}. */
    public ViewCounters counters() { return stats; }

    /** Pairs in the pipelines of online players now (a snapshot from the channels). */
    public int pairsInPipelines() {
        if (nms == null) return 0;
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { if (ViewTap.attached(nms.channel(p))) n++; } catch (RuntimeException ignored) { }
        }
        return n;
    }

    // ---- virtual viewers (proofs) ----------------------------------------------------------------------------------

    public ViewerRef virtual(UUID id, String name, World w, Consumer<Object> sink) {
        if (!supported()) throw new IllegalStateException("the packet view does not run on this server: " + unsupported);
        ViewerSlot old = virtual.remove(id);
        if (old != null) leave(old);
        ViewerSlot v = new ViewerSlot(newRef(id, name), null, w.getUID(), w.getMinHeight(), (w.getMaxHeight() - w.getMinHeight()) >> 4, sink == null ? packet -> { } : sink);
        virtual.put(id, v);
        return v.ref;
    }

    public void removeVirtual(ViewerRef ref) {
        ViewerSlot v = virtual.remove(ref.id());
        if (v != null) leave(v);
    }

    /** Runs a packet object for a virtual viewer: null dropped, the packet passed unchanged, or the body bytes after the byte side. */
    public Object filterVirtual(ViewerRef ref, Object packet) throws ReflectiveOperationException {
        ViewerSlot v = virtual.get(ref.id());
        if (v == null) throw new IllegalArgumentException("no virtual viewer " + ref);
        UUID w = v.state.world();
        Object d = router.decide(v, packet, w);
        if (d == ViewRules.DROP) return null;
        boolean unchanged = d == ViewRules.UNMASKED;
        if (!unchanged && !(d instanceof ViewKind)) return d;
        byte[] body = nms.encode(packet);
        if (unchanged) return body;
        byte[] whole = new byte[body.length + 1];
        System.arraycopy(body, 0, whole, 1, body.length);
        byte[] out = router.rewrite(v, (ViewKind) d, whole, w);
        if (out == null) return null;
        router.delivered(v, (ViewKind) d, whole, w);
        return java.util.Arrays.copyOfRange(out, 1, out.length);
    }

    /** Pure rewrite of one encoded packet (packet id first) for a virtual viewer, as the byte side would do it. */
    public byte[] rewriteVirtual(ViewerRef ref, String kind, byte[] whole) {
        ViewerSlot v = virtual.get(ref.id());
        if (v == null) throw new IllegalArgumentException("no virtual viewer " + ref);
        return router.rewrite(v, ViewKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)), whole, v.state.world());
    }
}
