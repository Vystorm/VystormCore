package Vystorm.vystorm_core.screen.internal;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Keeps container windows off the client while a chest menu is shown as a screen (since 0.58.0): the server opens the
 * menu for real, so plugins see it as open and get their normal close event, but the "open screen" packet is dropped
 * for players in {@link #hide}. A Netty handler per player, attached on first use; container contents for a window the
 * client never opened are ignored by the client. If the channel cannot be reached, {@link #hide} returns false and the
 * caller keeps the normal chest. Thread-safe.
 */
final class ScreenPackets {
    private static final String HANDLER = "vystorm_core_screen_windows";
    private static final Set<String> OPEN_SCREEN = Set.of("ClientboundOpenScreenPacket", "PacketPlayOutOpenWindow");

    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    /** Drops window packets for the player from now on; false when the connection cannot be hooked. */
    boolean hide(Player player) {
        if (!attach(player)) return false;
        hidden.add(player.getUniqueId());
        return true;
    }

    /** Lets window packets through again. */
    void show(Player player) { hidden.remove(player.getUniqueId()); }

    boolean hidden(UUID player) { return hidden.contains(player); }

    /** Whether a packet with this simple class name opens a container window. */
    static boolean opensWindow(String simpleName) { return OPEN_SCREEN.contains(simpleName); }

    private boolean attach(Player player) {
        try {
            Channel channel = channel(player);
            if (channel.pipeline().get(HANDLER) != null) return true;
            UUID id = player.getUniqueId();
            channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
                @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
                    if (hidden.contains(id) && opensWindow(packet.getClass().getSimpleName())) {
                        promise.setSuccess();
                        return;
                    }
                    super.write(context, packet, promise);
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static Channel channel(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = field(handle, "connection", "PlayerConnection", "ServerGamePacketListenerImpl");
        Object network = field(listener, "connection", "NetworkManager", "Connection");
        return (Channel) field(network, "channel", "Channel");
    }

    private static Object field(Object object, String name, String... types) throws ReflectiveOperationException {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (NoSuchFieldException absent) { }
        }
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                for (String type : types) if (f.getType().getSimpleName().equals(type)) { f.setAccessible(true); return f.get(object); }
            }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }
}
