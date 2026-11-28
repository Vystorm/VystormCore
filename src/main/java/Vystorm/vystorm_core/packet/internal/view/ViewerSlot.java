package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.view.ViewerRef;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * One viewer of the packet view: a player's connection (or a virtual viewer of a proof): its {@link ViewerRef}, what
 * its client holds, the chunks asked again after they could not be rewritten, and the packet objects Core sends
 * unmasked. Moved from VystormAntiXray's {@code Viewer} without the reveal and entity parts (they stay in that plugin).
 *
 * <p>Internal: not part of the public API.
 */
public final class ViewerSlot {
    public final ViewerRef ref;
    public final UUID id;
    public final String name;
    /** Null for a virtual viewer. */
    public final Player player;
    public final ClientWorldState state;
    /** How packets reach this viewer's client (a real player: the server connection, through the view). */
    public final Consumer<Object> sink;
    /** How often a chunk that could not be rewritten was asked again (chunk key to count). */
    public final Map<Long, Integer> retried = new ConcurrentHashMap<>();
    /** Packet objects that pass every layer unchanged ({@code sendReal(.., unmasked = true)}); taken once. */
    private final Set<Object> unmasked = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Whether Core's handlers are in this viewer's connection. */
    public volatile boolean attached;

    public ViewerSlot(ViewerRef ref, Player player, UUID world, int minY, int sections, Consumer<Object> sink) {
        this.ref = ref;
        this.id = ref.id();
        this.name = ref.name();
        this.player = player;
        this.state = new ClientWorldState(world, minY, sections);
        this.sink = sink;
    }

    /** Marks a packet object to pass every layer once (server thread, before it is sent). */
    public void passUnmasked(Object packet) { unmasked.add(packet); }

    /** Whether this packet object was marked; the mark is taken (Netty thread). */
    public boolean takeUnmasked(Object packet) { return !unmasked.isEmpty() && unmasked.remove(packet); }
}
