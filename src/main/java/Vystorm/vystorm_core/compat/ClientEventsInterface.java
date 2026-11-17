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
import Vystorm.vystorm_core.compat.ClientEvents.*;

/**
 * Manager view of {@link ClientEvents}, reached through {@code api.compat().clientEvents()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ClientEvents}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ClientEventsInterface {
    /** The delegating default. Plugins use {@code api.compat().clientEvents()}. */
    ClientEventsInterface DEFAULT = new ClientEventsInterface() {};

    /** See {@link ClientEvents#sentKeys}. */
    default Set<Long> sentKeys(Player p) { return ClientEvents.sentKeys(p); }

    /** See {@link ClientEvents#sent}. */
    default boolean sent(Player p, long key) { return ClientEvents.sent(p, key); }

    /** See {@link ClientEvents#sentChunks}. */
    default Set<Chunk> sentChunks(Player p) { return ClientEvents.sentChunks(p); }
}
