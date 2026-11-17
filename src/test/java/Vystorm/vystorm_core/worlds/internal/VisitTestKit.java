package Vystorm.vystorm_core.worlds.internal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.region.Region;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Testhilfen: Server-Umgebung, Welten und Spieler ohne Server. Alle Ereignisse landen in {@link Env#events}. */
final class VisitTestKit {
    private VisitTestKit() {}

    static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getUID()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        return world;
    }

    static ItemStack item(String name) {
        ItemStack stack = mock(ItemStack.class, name);
        when(stack.isEmpty()).thenReturn(false);
        when(stack.toString()).thenReturn(name);
        return stack;
    }

    static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    /** Welten-Register mit festen Einträgen. */
    static final class Worlds implements WorldLookup {
        final Map<String, ManagedWorldImpl> byId = new LinkedHashMap<>();

        void add(ManagedWorldImpl world) { byId.put(world.id(), world); }

        @Override public ManagedWorldImpl byId(String id) { return byId.get(id); }

        @Override public ManagedWorldImpl byWorld(World world) {
            for (ManagedWorldImpl w : byId.values()) if (w.world() != null && w.world().equals(world)) return w;
            return null;
        }

        @Override public Collection<ManagedWorldImpl> all() { return byId.values(); }
    }

    /** Merkt sich kodierte Item-Felder unter einem Schlüssel (statt Base64). */
    static final class Codec implements ItemCodec {
        private final Map<String, ItemStack[]> data = new HashMap<>();
        private int next;
        RuntimeException encodeFailure, decodeFailure;

        @Override public String encode(ItemStack[] items) {
            if (encodeFailure != null) throw encodeFailure;
            for (var entry : data.entrySet()) if (Arrays.equals(entry.getValue(), items)) return entry.getKey();
            String key = "c" + next++;
            data.put(key, items.clone());
            return key;
        }

        @Override public ItemStack[] decode(String key) {
            if (decodeFailure != null) throw decodeFailure;
            ItemStack[] items = data.get(key);
            return items == null ? new ItemStack[0] : items.clone();
        }
    }

    static final class Env implements VisitEnv {
        final List<String> events = new ArrayList<>();
        final Deque<Runnable> ticks = new ArrayDeque<>();
        final Deque<Runnable> deadlines = new ArrayDeque<>();
        final Map<UUID, FakePlayer> players = new HashMap<>();
        final Map<String, World> worlds = new HashMap<>();
        final Set<UUID> markers = new HashSet<>();
        final Set<ItemStack> tools = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ItemStack> legacyTools = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, String> commands = new HashMap<>();
        final List<String> messages = new ArrayList<>(), logs = new ArrayList<>();
        final List<ItemStack> drops = new ArrayList<>();
        final Codec codec = new Codec();
        Location mainSpawn;
        long now = 1_000_000L;
        /** {@code null} = Teleport bleibt offen ({@link #pending}, {@link #complete}). */
        Boolean asyncTeleport = true;
        CompletableFuture<Boolean> pending;
        final List<Object[]> pendings = new ArrayList<>();   // {player, target, future}

        /** Offenen Teleport Nr. {@code index} abschließen (bei Erfolg wird der Spieler bewegt). */
        void complete(int index, boolean ok) {
            Object[] entry = pendings.get(index);
            if (ok) players.get(((Player) entry[0]).getUniqueId()).moveTo((Location) entry[1]);
            @SuppressWarnings("unchecked") CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) entry[2];
            future.complete(ok);
        }
        boolean syncTeleport = true;
        RuntimeException regionFailure;
        boolean pendingMigration;
        public boolean pendingMigration(UUID id) { return pendingMigration; }
        List<Region> regions = List.of();
        BiPredicate<UUID, UUID> party = (a, b) -> false;

        Env(World main) {
            worlds.put(main.getName(), main);
            mainSpawn = new Location(main, 0, 64, 0);
        }

        void runTicks() { while (!ticks.isEmpty()) ticks.poll().run(); }

        boolean primary = true;
        @Override public boolean primaryThread() { return primary; }
        @Override public long now() { return now; }
        @Override public void nextTick(Runnable task) { ticks.add(task); }
        @Override public void later(long ticks, Runnable task) { deadlines.add(task); }

        @Override public CompletableFuture<Boolean> teleportAsync(Player player, Location to) {
            events.add("teleport-async " + to.getWorld().getName());
            if (asyncTeleport == null) {
                pending = new CompletableFuture<>();
                pendings.add(new Object[]{player, to.clone(), pending});
                return pending;
            }
            if (asyncTeleport) players.get(player.getUniqueId()).moveTo(to);
            return CompletableFuture.completedFuture(asyncTeleport);
        }

        @Override public boolean teleport(Player player, Location to) {
            events.add("teleport " + to.getWorld().getName() + (syncTeleport ? "" : " (failed)"));
            if (syncTeleport) players.get(player.getUniqueId()).moveTo(to);
            return syncTeleport;
        }

        @Override public Location mainSpawn() { return mainSpawn.clone(); }
        @Override public World world(String name) { return worlds.get(name); }
        @Override public Player player(UUID id) { FakePlayer p = players.get(id);return p == null || !p.online ? null : p.player; }

        @Override public List<Region> regionsAt(Location location) {
            if (regionFailure != null) throw regionFailure;
            return regions;
        }

        @Override public boolean sameParty(UUID a, UUID b) { return party.test(a, b); }
        @Override public String commandName(String label) { return commands.get(label); }
        @Override public void hide(Player viewer, Player other) { events.add("hide " + viewer.getName() + " " + other.getName()); }
        @Override public void show(Player viewer, Player other) { events.add("show " + viewer.getName() + " " + other.getName()); }
        @Override public boolean hasMarker(Player player) { return markers.contains(player.getUniqueId()); }
        @Override public void setMarker(Player player, String worldId) { events.add("marker set");markers.add(player.getUniqueId()); }
        @Override public void clearMarker(Player player) { events.add("marker cleared");markers.remove(player.getUniqueId()); }

        @Override public boolean isTool(ItemStack stack, String legacyKey) {
            return stack != null && (tools.contains(stack) || legacyKey != null && legacyTools.contains(stack));
        }

        @Override public ItemStack markTool(ItemStack stack, String worldId) {
            ItemStack marked = item("tool:" + stack);
            tools.add(marked);
            return marked;
        }

        @Override public ItemCodec codec() { return codec; }
        @Override public void message(Player player, String key, Object... placeholders) { messages.add(key); }
        @Override public void log(String message) { logs.add(message); }
        @Override public void drop(Location at, ItemStack stack) { drops.add(stack); }
    }

    static class Writes extends java.util.concurrent.AbstractExecutorService {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final boolean immediate;
        boolean stopped;
        Writes(boolean immediate) { this.immediate = immediate; }
        @Override public void execute(Runnable task) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            if (immediate) task.run(); else tasks.add(task);
        }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true;return List.of(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long n, java.util.concurrent.TimeUnit unit) { return stopped; }
    }

    /** Spieler-Mock mit echtem Inventar-Array (41 Plätze), Cursor, Ort und Spielmodus. */
    static final class FakePlayer {
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final Env env;
        ItemStack[] contents = new ItemStack[41];
        ItemStack cursor;
        Location location;
        GameMode mode = GameMode.SURVIVAL;
        boolean online = true;
        RuntimeException saveFailure;
        int saves;

        FakePlayer(Env env, String name, Location at) {
            this.env = env;
            this.location = at;
            UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            env.players.put(id, this);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenAnswer(i -> online);
            when(player.getWorld()).thenAnswer(i -> location.getWorld());
            when(player.getLocation()).thenAnswer(i -> location.clone());
            doAnswer(i -> {
                List<ItemStack> closing = new ArrayList<>();
                if (cursor != null) closing.add(cursor);
                cursor = null;
                var crafting = PlayerStates.crafting(player);
                if (crafting != null) {
                    for (ItemStack stack : crafting.getMatrix()) if (stack != null) closing.add(stack);
                    ItemStack[] empty = new ItemStack[crafting.getMatrix().length];
                    crafting.setMatrix(empty);
                    when(crafting.getMatrix()).thenReturn(empty);
                }
                for (ItemStack rest : PlayerStates.give(player, closing)) env.drop(location, rest);
                return null;
            }).when(player).closeInventory();
            when(player.getGameMode()).thenAnswer(i -> mode);
            doAnswer(i -> { mode = i.getArgument(0);env.events.add("gamemode " + mode);return null; }).when(player).setGameMode(any());
            when(player.getInventory()).thenReturn(inventory);
            when(player.getItemOnCursor()).thenAnswer(i -> cursor);
            doAnswer(i -> { cursor = i.getArgument(0);return null; }).when(player).setItemOnCursor(any());
            doAnswer(i -> {
                env.events.add("save");
                if (saveFailure != null) throw saveFailure;
                saves++;
                return null;
            }).when(player).saveData();
            when(inventory.getSize()).thenReturn(41);
            when(inventory.getContents()).thenAnswer(i -> contents.clone());
            when(inventory.getItem(anyInt())).thenAnswer(i -> contents[(int) i.getArgument(0)]);
            doAnswer(i -> { contents[(int) i.getArgument(0)] = i.getArgument(1);return null; }).when(inventory).setItem(anyInt(), any());
            doAnswer(i -> {
                ItemStack[] next = i.getArgument(0);
                contents = Arrays.copyOf(next, 41);
                env.events.add("set-contents");
                return null;
            }).when(inventory).setContents(any(ItemStack[].class));
            doAnswer(i -> { Arrays.fill(contents, null);env.events.add("clear");return null; }).when(inventory).clear();
            doAnswer(i -> {
                HashMap<Integer, ItemStack> rest = new HashMap<>();
                ItemStack[] items = (ItemStack[]) i.getRawArguments()[0];
                for (int k = 0; k < items.length; k++) {
                    int free = -1;
                    for (int s = 0; s < 36; s++) if (contents[s] == null) { free = s;break; }
                    if (free < 0) rest.put(k, items[k]);
                    else contents[free] = items[k];
                }
                return rest;
            }).when(inventory).addItem(any(ItemStack[].class));
        }

        void moveTo(Location to) { location = to.clone(); }

        List<ItemStack> items() {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack stack : contents) if (stack != null) result.add(stack);
            return result;
        }
    }
}
