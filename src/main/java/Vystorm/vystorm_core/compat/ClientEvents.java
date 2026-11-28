package Vystorm.vystorm_core.compat;

import io.netty.channel.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Actual client chunk delivery and virtual entity interactions across Bukkit and Paper. @since 0.51.0
 * <p>Preferred access: {@code api.compat().clientEvents()}.
 */
public final class ClientEvents implements Listener {
    private static final String HANDLER = "vystorm_core_client_events";
    private static final Map<UUID, State> PLAYERS = new ConcurrentHashMap<>();
    private static Plugin owner;
    private static boolean paper;

    private static final class State {
        final Player player;
        final Set<Long> chunks = ConcurrentHashMap.newKeySet();
        volatile UUID world;
        volatile Channel channel;

        State(Player p) {
            player = p;
            world = p.getWorld().getUID();
        }
    }

    private ClientEvents() {}

    public static void initialize(Plugin core) {
        owner = core;
        paper = ServerCompat.hasClass("io.papermc.paper.event.packet.PlayerChunkLoadEvent");
        Bukkit.getPluginManager().registerEvents(new ClientEvents(), core);
        if (paper) Bukkit.getPluginManager().registerEvents(new PaperClientEvents(), core);
        for (Player p : Bukkit.getOnlinePlayers()) attach(p);
    }

    public static void shutdown() {
        for (State state : PLAYERS.values()) detach(state);
        PLAYERS.clear();
        owner = null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent e) {
        attach(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        State old = PLAYERS.remove(e.getPlayer().getUniqueId());
        if (old != null) detach(old);
    }

    @EventHandler
    public void world(PlayerChangedWorldEvent e) {
        State state = PLAYERS.get(e.getPlayer().getUniqueId());
        if (state != null) {
            state.chunks.clear();
            state.world = e.getPlayer().getWorld().getUID();
        }
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        State state = PLAYERS.get(e.getPlayer().getUniqueId());
        if (state != null) {
            state.chunks.clear();
            state.world = e.getRespawnLocation().getWorld().getUID();
        }
    }

    // 0.72.0: Spigot has neither Paper method; remember that instead of throwing NoSuchMethodError on every call.
    private static volatile boolean noNativeKeys, noNativeSent;

    public static Set<Long> sentKeys(Player p) {
        if (!noNativeKeys) {
            try {
                return p.getSentChunkKeys();
            } catch (NoSuchMethodError missing) {
                noNativeKeys = true;
            }
        }
        State state = PLAYERS.get(p.getUniqueId());
        return state == null ? Set.of() : Set.copyOf(state.chunks);
    }

    public static boolean sent(Player p, long key) {
        if (!noNativeSent) {
            try {
                return p.isChunkSent(key);
            } catch (NoSuchMethodError missing) {
                noNativeSent = true;
            }
        }
        State state = PLAYERS.get(p.getUniqueId());
        return state != null && state.world.equals(p.getWorld().getUID()) && state.chunks.contains(key);
    }

    public static Set<Chunk> sentChunks(Player p) {
        Set<Chunk> chunks = new HashSet<>();
        World world = p.getWorld();
        for (long key : sentKeys(p)) {
            int x = (int) key, z = (int) (key >> 32);
            if (world.isChunkLoaded(x, z)) chunks.add(world.getChunkAt(x, z));
        }
        return chunks;
    }

    static void loaded(Player p, Chunk chunk) {
        State state = PLAYERS.computeIfAbsent(p.getUniqueId(), ignored -> new State(p));
        state.chunks.add(PlatformCompat.getChunkKey(chunk));
        Bukkit.getPluginManager().callEvent(new Loaded(p, chunk));
    }

    static void unloaded(Player p, Chunk chunk) {
        State state = PLAYERS.get(p.getUniqueId());
        if (state != null) state.chunks.remove(PlatformCompat.getChunkKey(chunk));
        Bukkit.getPluginManager().callEvent(new Unloaded(p, chunk));
    }

    static void interact(Player p, int id, boolean attack, EquipmentSlot hand) {
        Bukkit.getPluginManager().callEvent(new Interact(p, id, attack, hand));
    }

    private static void attach(Player p) {
        State state = new State(p);
        State old = PLAYERS.put(p.getUniqueId(), state);
        if (old != null) detach(old);
        if (paper) return;
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object listener = field(handle, "connection", "PlayerConnection", "ServerGamePacketListenerImpl");
            Object network = field(listener, "connection", "NetworkManager", "Connection");
            Channel channel = (Channel) field(network, "channel", "Channel");
            state.channel = channel;
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
                channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
                    @Override
                    public void write(
                            ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                        super.write(context, packet, promise);
                        observe(state, packet);
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
                        String name = packet.getClass().getSimpleName();
                        if (name.equals("ServerboundInteractPacket") || name.equals("ServerboundAttackPacket")
                                || name.equals("PacketPlayInUseEntity")) {
                            try {
                                Interaction click = interaction(packet);
                                runMain(state, () -> interact(p, click.id, click.attack, click.hand));
                            } catch (ReflectiveOperationException failure) {
                                report(failure);
                            }
                        }
                        super.channelRead(context, packet);
                    }
                });
            });
        } catch (ReflectiveOperationException failure) {
            report(failure);
        }
    }

    record Interaction(int id, boolean attack, EquipmentSlot hand) {}

    static Interaction interaction(Object packet) throws ReflectiveOperationException {
        int id = ((Number) field(packet, "entityId", "int")).intValue();
        if (packet.getClass().getSimpleName().equals("ServerboundAttackPacket"))
            return new Interaction(id, true, EquipmentSlot.HAND);
        Object hand;
        boolean attack = false;
        try {
            hand = field(packet, "hand", "InteractionHand", "EnumHand");
        } catch (NoSuchFieldException legacyPacket) {
            Object action = field(packet, "action", "Action");
            Method kind = action.getClass().getDeclaredMethod("getType");
            kind.setAccessible(true);
            attack = kind.invoke(action).toString().equals("ATTACK");
            try {
                hand = field(action, "hand", "InteractionHand", "EnumHand");
            } catch (NoSuchFieldException noHand) {
                hand = "MAIN_HAND";
            }
        }
        return new Interaction(
                id, attack, hand.toString().contains("OFF") ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
    }

    private static void observe(State state, Object packet) {
        String name = packet.getClass().getSimpleName();
        try {
            if (name.equals("ClientboundLevelChunkWithLightPacket") || name.equals("PacketPlayOutMapChunk")) {
                long coordinates = chunkKey(packet);
                int x = (int) coordinates, z = (int) (coordinates >> 32);
                long key = PlatformCompat.getChunkKey(x, z);
                state.chunks.add(key);
                runMain(state, () -> {
                    World world = state.player.getWorld();
                    if (world.isChunkLoaded(x, z)) loaded(state.player, world.getChunkAt(x, z));
                });
            } else if (name.equals("ClientboundForgetLevelChunkPacket") || name.equals("PacketPlayOutUnloadChunk")) {
                Object pos = packet.getClass().getMethod("pos").invoke(packet);
                int x = ((Number) field(pos, "x", "int")).intValue(), z = ((Number) field(pos, "z", "int")).intValue();
                state.chunks.remove(PlatformCompat.getChunkKey(x, z));
                runMain(state, () -> {
                    World world = state.player.getWorld();
                    if (world.isChunkLoaded(x, z)) unloaded(state.player, world.getChunkAt(x, z));
                });
            } else if (name.equals("ClientboundBundlePacket")) {
                Object packets = packet.getClass().getMethod("subPackets").invoke(packet);
                for (Object child : (Iterable<?>) packets) observe(state, child);
            }
        } catch (ReflectiveOperationException failure) {
            report(failure);
        }
    }

    static long chunkKey(Object packet) throws ReflectiveOperationException {
        // 26.2 exposes getX/getZ; 26.3 is a record with x/z accessors. Their stored fields are stable.
        return PlatformCompat.getChunkKey(
                ((Number) field(packet, "x")).intValue(), ((Number) field(packet, "z")).intValue());
    }

    private static void runMain(State state, Runnable action) {
        Plugin plugin = owner;
        if (plugin == null || !plugin.isEnabled()) return;
        UUID world = state.world;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (state.player.isOnline() && state.player.getWorld().getUID().equals(world)) action.run();
        });
    }

    private static void detach(State state) {
        Channel channel = state.channel;
        if (channel != null)
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
            });
    }

    private static Object field(Object object, String name, String... types) throws ReflectiveOperationException {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass())
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (NoSuchFieldException absent) {}
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                for (String type : types)
                    if (f.getType().getSimpleName().equals(type)) {
                        f.setAccessible(true);
                        return f.get(object);
                    }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static volatile boolean reported;

    private static void report(Exception error) {
        Plugin plugin = owner;
        if (plugin != null && !reported) {
            reported = true;
            plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Client packet tracking failed; client-only effects cannot be certified", error);
        }
    }

    public static final class Loaded extends PlayerEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Chunk chunk;

        Loaded(Player p, Chunk c) {
            super(p);
            chunk = c;
        }

        public Chunk getChunk() {
            return chunk;
        }

        public World getWorld() {
            return chunk.getWorld();
        }

        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class Unloaded extends PlayerEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Chunk chunk;

        Unloaded(Player p, Chunk c) {
            super(p);
            chunk = c;
        }

        public Chunk getChunk() {
            return chunk;
        }

        public World getWorld() {
            return chunk.getWorld();
        }

        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    public static final class Interact extends PlayerEvent {
        private static final HandlerList HANDLERS = new HandlerList();
        private final int id;
        private final boolean attack;
        private final EquipmentSlot hand;

        Interact(Player p, int id, boolean attack, EquipmentSlot hand) {
            super(p);
            this.id = id;
            this.attack = attack;
            this.hand = hand;
        }

        public int getEntityId() {
            return id;
        }

        public boolean isAttack() {
            return attack;
        }

        public EquipmentSlot getHand() {
            return hand;
        }

        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
