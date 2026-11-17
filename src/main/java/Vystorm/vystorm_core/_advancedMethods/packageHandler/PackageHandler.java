package Vystorm.vystorm_core._advancedMethods.packageHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.*;
import java.util.*;

/** Uses public custom-payload channels, without version-bound NMS packet reflection. */
public class PackageHandler implements PackageInterface {
    private record Key(Plugin owner, String channel) {}
    private record Registration(PluginMessageListener listener, boolean ownsOutgoing) {}
    private final Map<Key, Registration> channels = new HashMap<>();
    private void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Plugin messaging requires the server thread");
    }
    private Key key(Plugin owner, String channel) {
        return new Key(Objects.requireNonNull(owner, "owner"), StandardMessenger.validateAndCorrectChannel(channel));
    }
    @Override public void register(Plugin owner, String channel, PluginMessageListener listener) {
        thread();
        Key key = key(owner, channel);
        if (!owner.isEnabled()) throw new IllegalArgumentException("Owner plugin is disabled");
        if (channels.containsKey(key)) throw new IllegalArgumentException("Channel already registered for this plugin");
        Messenger messenger = Bukkit.getMessenger();
        boolean ownsOutgoing = !messenger.isOutgoingChannelRegistered(owner, key.channel());
        PluginMessageListener wrapped = listener == null ? null : (name, player, payload) -> {
            if (!owner.isEnabled()) return;
            try { listener.onPluginMessageReceived(name, player, payload.clone()); }
            catch (RuntimeException failure) {
                DebugMessages.high("[PackageHandler] Plugin message callback failed; channel=" + name);
                throw failure;
            }
            DebugMessages.low("[PackageHandler] Plugin message received; channel=" + name + "; bytes=" + payload.length);
        };
        if (ownsOutgoing) messenger.registerOutgoingPluginChannel(owner, key.channel());
        try {
            if (wrapped != null) messenger.registerIncomingPluginChannel(owner, key.channel(), wrapped);
        } catch (RuntimeException failure) {
            if (ownsOutgoing) messenger.unregisterOutgoingPluginChannel(owner, key.channel());
            throw failure;
        }
        channels.put(key, new Registration(wrapped, ownsOutgoing));
        DebugMessages.mid("[PackageHandler] Plugin message channel registered; channel=" + key.channel());
    }
    @Override public void send(Plugin owner, Player player, String channel, byte[] payload) {
        thread();
        Key key = key(owner, channel);
        Objects.requireNonNull(player, "player"); Objects.requireNonNull(payload, "payload");
        if (!channels.containsKey(key)) throw new IllegalArgumentException("Channel is not registered through this handler");
        if (!owner.isEnabled() || !player.isOnline()) throw new IllegalStateException("Plugin and player must be active");
        if (payload.length > Messenger.MAX_MESSAGE_SIZE) throw new IllegalArgumentException("Plugin message exceeds maximum size");
        player.sendPluginMessage(owner, key.channel(), payload.clone());
        DebugMessages.low("[PackageHandler] Plugin message sent; channel=" + key.channel() + "; bytes=" + payload.length);
    }
    @Override public boolean unregister(Plugin owner, String channel) {
        thread();
        Key key = key(owner, channel);
        Registration registration = channels.remove(key);
        if (registration == null) return false;
        Messenger messenger = Bukkit.getMessenger();
        if (registration.listener() != null) messenger.unregisterIncomingPluginChannel(owner, key.channel(), registration.listener());
        if (registration.ownsOutgoing()) messenger.unregisterOutgoingPluginChannel(owner, key.channel());
        DebugMessages.mid("[PackageHandler] Plugin message channel unregistered; channel=" + key.channel());
        return true;
    }
    @Override public void removeAll(Plugin owner) {
        thread();
        for (Key key : new ArrayList<>(channels.keySet())) if (key.owner().equals(owner)) unregister(owner, key.channel());
    }
    @Override public void clear() {
        thread();
        for (Key key : new ArrayList<>(channels.keySet())) unregister(key.owner(), key.channel());
    }
}
