package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.network.Network;
import Vystorm.vystorm_core.network.NetworkListener;
import Vystorm.vystorm_core.network.NetworkServer;
import Vystorm.vystorm_core.network.Subscription;
import Vystorm.vystorm_core.network.core.ChannelMessage;
import Vystorm.vystorm_core.network.core.Frame;
import Vystorm.vystorm_core.network.core.Handshake;
import Vystorm.vystorm_core.network.core.HubClient;
import Vystorm.vystorm_core.network.core.KeyFile;
import Vystorm.vystorm_core.network.core.NetworkLog;
import Vystorm.vystorm_core.network.core.NetworkServerInfo;
import Vystorm.vystorm_core.network.core.Pairing;
import Vystorm.vystorm_core.network.core.PairingGate;
import Vystorm.vystorm_core.network.core.ParticipantNode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Core's participant on a Paper/Spigot server: connects to the hub on the proxy, learns its name from the proxy,
 * handles pairing and implements the public {@link Network}. Started from {@code main.onEnable}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PaperNetwork implements Network, PluginMessageListener {
    public static final String BROADCAST_CHANNEL = "vystorm:broadcast";
    private static volatile PaperNetwork current;

    private final Plugin plugin;
    private final ParticipantSettings settings;
    private final NetworkLog log;
    private final Executor mainThread;
    private final ParticipantNode node;
    private final PairingGate gate = new PairingGate(Clock.systemUTC());
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> broadcastSinks = new CopyOnWriteArrayList<>();
    private volatile HubClient client;
    private volatile boolean stopped;

    private PaperNetwork(Plugin plugin, ParticipantSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.log = NetworkLog.of(plugin.getLogger());
        this.mainThread = task -> { if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, task); };
        this.node = new ParticipantNode(mainThread, log);
    }

    /** The running network, or {@link DisconnectedNetwork} when there is none. */
    public static Network network() {
        PaperNetwork n = current;
        return n == null ? DisconnectedNetwork.INSTANCE : n;
    }

    public static Optional<PaperNetwork> running() { return Optional.ofNullable(current); }

    public static synchronized void start(Plugin plugin) {
        stop();
        FileConfiguration config = plugin.getConfig();
        Path serverRoot = plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile().toPath();
        ParticipantSettings settings = new ParticipantSettings(
                config.getString("network.enabled", "auto"),
                config.getString("network.key", ""),
                config.getString("network.server-name", ""),
                config.getString("network.hub.host", ""),
                config.getInt("network.hub.port", 0),
                serverRoot.resolve("config").resolve("paper-global.yml"),
                plugin.getDataFolder().toPath().resolve("network"));
        if (settings.mode() == ParticipantSettings.Mode.OFF) return;
        PaperNetwork network = new PaperNetwork(plugin, settings);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ChannelMessage.CHANNEL, network);
        network.subscribeInternal(BROADCAST_CHANNEL, (from, payload) -> network.showBroadcast(new String(payload, java.nio.charset.StandardCharsets.UTF_8)));
        current = network;
        network.connectIfReady();
    }

    public static synchronized void stop() {
        PaperNetwork n = current;
        current = null;
        if (n == null) return;
        n.stopped = true;
        for (Subscription s : n.subscriptions) s.close();
        HubClient c = n.client;
        if (c != null) c.stop();
        try { n.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(n.plugin, ChannelMessage.CHANNEL, n); }
        catch (RuntimeException alreadyGone) { /* server shutting down */ }
    }

    private synchronized void connectIfReady() {
        if (stopped || client != null) return;
        Optional<String> name = settings.serverName();
        if (name.isEmpty()) return; // waits for the proxy's identity message
        HubClient c = new HubClient(settings::hubAddress, name.get(), settings::key, node, log);
        node.attach(c);
        client = c;
        c.start();
    }

    // ---- plugin messaging (proxy → server only; the proxy drops client messages on this channel) ----

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ChannelMessage.CHANNEL.equals(channel) || stopped) return;
        Optional<ChannelMessage> decoded = ChannelMessage.decode(message);
        if (decoded.isEmpty()) return;
        switch (decoded.get()) {
            case ChannelMessage.Identity identity -> onIdentity(identity);
            case ChannelMessage.Offer offer -> onOffer(offer);
        }
    }

    private void onIdentity(ChannelMessage.Identity identity) {
        if (node.connected()) return; // never redirect a working connection
        try {
            if (settings.learn(identity)) {
                log.info("Proxy announced this server as '" + identity.serverName() + "', hub " + identity.hubHost() + ":" + identity.hubPort());
                HubClient c = client;
                if (c != null && !c.serverName().equals(settings.serverName().orElse(""))) {
                    c.stop();
                    client = null;
                }
            }
        } catch (IOException failure) {
            log.warn("Could not remember the hub address: " + failure.getMessage());
        }
        connectIfReady();
        HubClient c = client;
        if (c != null && !c.connected()) c.reconnectNow();
    }

    private void onOffer(ChannelMessage.Offer message) {
        boolean hasKey = settings.key().isPresent();
        Optional<Pairing.ClientSide> pending = gate.acceptOffer(message.offer(), hasKey);
        if (pending.isEmpty()) return;
        Pairing.ClientSide side = pending.get();
        Thread.ofVirtual().name("vystorm-net-pair").start(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(side.offer().hubHost(), side.offer().hubPort()), 5000);
                socket.setSoTimeout(5000);
                Handshake.sendPairReply(new BufferedInputStream(socket.getInputStream()),
                        new BufferedOutputStream(socket.getOutputStream()), side.offer(), message.serverName(), side.publicKey());
                log.warn("Pairing request from proxy '" + side.offer().hubId() + "'. Check that the proxy console shows code "
                        + side.code() + ", then run: vnet pair accept " + side.code() + " (expires in 10 minutes)");
            } catch (IOException failure) {
                gate.unpair();
                log.warn("Pairing with the proxy failed: " + failure.getMessage());
            }
        });
    }

    /** {@code /vnet pair accept <code>}: stores the key only when the code matches. */
    public boolean confirmPairing(String code) {
        Optional<Pairing.ClientSide> confirmed = gate.confirm(code);
        if (confirmed.isEmpty()) return false;
        Pairing.ClientSide side = confirmed.get();
        try {
            KeyFile.save(settings.keyFile(), side.key());
            String name = settings.serverName().orElse("");
            if (!name.isEmpty()) settings.rememberPairedHub(name, side.offer().hubHost(), side.offer().hubPort());
        } catch (IOException failure) {
            log.warn("Could not save the network key: " + failure.getMessage());
            return false;
        }
        log.info("Paired with proxy '" + side.offer().hubId() + "' (key " + side.key().fingerprint() + ")");
        connectIfReady();
        HubClient c = client;
        if (c != null) c.reconnectNow();
        return true;
    }

    /** {@code /vnet unpair}: forgets the paired key so a new offer is accepted. */
    public void unpair() {
        gate.unpair();
        try { KeyFile.delete(settings.keyFile()); }
        catch (IOException failure) { log.warn("Could not delete the network key: " + failure.getMessage()); }
        HubClient c = client;
        if (c != null) c.reconnectNow();
    }

    public Optional<String> pendingPairingCode() { return gate.pending().map(Pairing.ClientSide::code); }
    public String keySource() { return settings.keySource(); }
    public Optional<String> keyFingerprint() { return settings.key().map(k -> k.fingerprint()); }
    public Optional<InetSocketAddress> hubAddress() { return settings.hubAddress(); }
    public long latencyMillis() { return node.latencyMillis(); }

    /** Runs on the main thread when the hub connection comes up or goes down (menus refresh with it). */
    public void onConnectionChange(Runnable listener) { node.onConnectionChange(listener); }

    /** {@code /vnet broadcast}: shows locally and on every other server. */
    public void broadcastText(String miniMessage) {
        showBroadcast(miniMessage);
        node.sendInternal(Frame.ALL, BROADCAST_CHANNEL, miniMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Where received broadcasts are shown (set by the command layer). */
    public void onBroadcast(Consumer<String> sink) { broadcastSinks.add(sink); }

    private void showBroadcast(String text) {
        for (Consumer<String> sink : broadcastSinks) sink.accept(text);
    }

    private void subscribeInternal(String channel, NetworkListener listener) {
        try {
            AutoCloseable handle = node.subscribe(channel, listener::onMessage);
            subscriptions.add(() -> { try { handle.close(); } catch (Exception ignored) { } });
        } catch (IllegalArgumentException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- Network ----

    @Override public boolean connected() { return node.connected(); }
    @Override public String serverName() { return settings.serverName().orElse(""); }

    @Override public Collection<NetworkServer> servers() {
        if (!node.connected()) return List.of();
        return node.state().servers().stream().map(PaperNetwork::toApi).toList();
    }

    private static NetworkServer toApi(NetworkServerInfo info) { return new NetworkServer(info.name(), info.connected(), info.players()); }

    @Override public Optional<String> serverOf(UUID player) { return node.state().serverOf(player); }

    @Override public CompletableFuture<Boolean> sendPlayer(UUID player, String server) { return node.sendPlayer(player, server); }

    @Override public void send(String server, String channel, byte[] payload) {
        DisconnectedNetwork.checkChannel(channel);
        node.send(server, channel, payload);
    }

    @Override public void sendToPlayer(UUID player, String channel, byte[] payload) {
        DisconnectedNetwork.checkChannel(channel);
        node.send(Frame.playerTarget(player), channel, payload);
    }

    @Override public void broadcast(String channel, byte[] payload) {
        DisconnectedNetwork.checkChannel(channel);
        node.broadcast(channel, payload);
    }

    @Override public Subscription subscribe(String channel, NetworkListener listener) {
        DisconnectedNetwork.checkChannel(channel);
        AutoCloseable handle = node.subscribe(channel, listener::onMessage);
        Subscription subscription = new Subscription() {
            private volatile boolean closed;
            @Override public void close() {
                if (closed) return;
                closed = true;
                subscriptions.remove(this);
                try { handle.close(); } catch (Exception ignored) { }
            }
        };
        subscriptions.add(subscription);
        return subscription;
    }
}
