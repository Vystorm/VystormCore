package Vystorm.vystorm_core.network.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the proxy side does, shared by the Velocity and BungeeCord adapters: settings in
 * {@code network.properties}, key choice per server (manual → Velocity modern-forwarding secret → paired key),
 * pairing offers and confirmation codes, the identity announcement and the hub itself.
 *
 * <p>Pairing: when a player reaches a server that has no key yet, the proxy sends IDENTITY and an OFFER over
 * {@code vystorm:net}. The server answers on the hub port; both consoles then show the same code and the server
 * admin confirms it. The hub keeps the derived key in memory until that server authenticates with it, and only
 * then stores it in {@code servers/<name>.key}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class HubRuntime {
    public static final String SETTINGS_FILE = "network.properties";

    /** Sends one {@code vystorm:net} message to the server a player is connected to. */
    public interface ServerChannel { void send(byte[] message); }

    private final Path dataDir;
    private final Optional<NetworkKey> velocityKey;
    private final NetworkLog log;
    private final Clock clock;
    private final Properties settings;
    private final HubNode node;
    private final Map<String, Pairing.HubSide> offers = new ConcurrentHashMap<>();
    private final Map<String, NetworkKey> pendingKeys = new ConcurrentHashMap<>();
    private HubServer server;

    public HubRuntime(Path dataDir, Optional<String> velocityForwardingSecret, ProxyBridge bridge, NetworkLog log, Clock clock) {
        this.dataDir = dataDir;
        this.velocityKey = velocityForwardingSecret.map(KeySources::fromVelocitySecret);
        this.log = log;
        this.clock = clock;
        this.settings = loadSettings(dataDir.resolve(SETTINGS_FILE), log);
        this.node = new HubNode(bridge, log);
        node.pairReplies(this::onPairReply);
    }

    public HubNode node() { return node; }

    public boolean enabled() { return !"false".equalsIgnoreCase(settings.getProperty("enabled", "true").trim()); }

    public HubConfig config() {
        List<String> allow = new ArrayList<>();
        for (String part : settings.getProperty("allow", "").split(",")) if (!part.isBlank()) allow.add(part.trim());
        return HubConfig.defaults().withAddress(settings.getProperty("bind-host", HubConfig.DEFAULT_HOST).trim(),
                parsePort(settings.getProperty("port", ""), HubConfig.DEFAULT_PORT), allow);
    }

    /** Host the servers should connect to (differs from bind-host when binding 0.0.0.0). */
    public String advertisedHost() {
        String advertise = settings.getProperty("advertise-host", "").trim();
        if (!advertise.isEmpty()) return advertise;
        String bind = config().bindHost();
        return bind.equals("0.0.0.0") || bind.equals("::") ? "127.0.0.1" : bind;
    }

    public String hubId() { return settings.getProperty("hub-id", "proxy").trim(); }

    /** Starts the hub; false if disabled or the port is taken (logged once). */
    public synchronized boolean start() {
        if (!enabled()) { log.info("Network hub disabled in " + SETTINGS_FILE); return false; }
        server = new HubServer(config(), this::keyFor, new PersistingHandler(), log);
        node.attach(server);
        boolean started = server.start();
        if (started) {
            log.info("Key source: " + keySourceDescription());
            log.warn("Velocity/BungeeCord support is EXPERIMENTAL in this version. Please report problems.");
        }
        return started;
    }

    public synchronized void stop() {
        if (server != null) server.stop();
        server = null;
    }

    public Optional<HubServer> server() { return Optional.ofNullable(server); }

    // ---- keys ----

    Optional<NetworkKey> manualKey() { return KeySources.fromBase64(settings.getProperty("key", "")); }

    /** True when every server shares one key, so no pairing is ever offered. */
    public boolean sharedKey() { return manualKey().isPresent() || velocityKey.isPresent(); }

    public Optional<NetworkKey> keyFor(String serverName) {
        if (!validName(serverName)) return Optional.empty();
        Optional<NetworkKey> manual = manualKey();
        if (manual.isPresent()) return manual;
        if (velocityKey.isPresent()) return velocityKey;
        Optional<NetworkKey> paired = KeyFile.load(pairedKeyFile(serverName));
        if (paired.isPresent()) return paired;
        return Optional.ofNullable(pendingKeys.get(serverName));
    }

    public String keySourceDescription() {
        if (manualKey().isPresent()) return "fixed key from " + SETTINGS_FILE + " (" + manualKey().get().fingerprint() + ")";
        if (velocityKey.isPresent()) return "Velocity modern forwarding secret (" + velocityKey.get().fingerprint() + ")";
        return "per-server pairing (confirm with /vnet pair accept <code> on each server)";
    }

    Path pairedKeyFile(String serverName) { return dataDir.resolve("servers").resolve(serverName + ".key"); }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,64}");
    }

    // ---- proxy events ----

    /**
     * A player is now connected to {@code serverName}. If Core on that server is not connected, announce the
     * identity and (without shared key and without stored pairing) offer pairing.
     */
    public void playerReachedServer(UUID player, String playerName, String serverName, boolean firstServer, ServerChannel channel) {
        if (firstServer) node.playerJoined(player, playerName, serverName);
        else node.playerSwitched(player, serverName);
        HubServer s = server;
        if (s == null || s.connected().contains(serverName) || !validName(serverName)) return;
        channel.send(ChannelMessage.encode(new ChannelMessage.Identity(serverName, advertisedHost(), s.port())));
        if (sharedKey() || KeyFile.load(pairedKeyFile(serverName)).isPresent() || pendingKeys.containsKey(serverName)) return;
        Pairing.HubSide offer = offers.compute(serverName, (name, existing) ->
                existing == null || existing.expired() || existing.compromised() || existing.key().isPresent()
                        ? Pairing.hubStart(hubId(), advertisedHost(), s.port(), clock) : existing);
        channel.send(ChannelMessage.encode(new ChannelMessage.Offer(serverName, offer.offer())));
    }

    public void playerLeft(UUID player) { node.playerLeft(player); }

    private boolean onPairReply(String serverName, String offerId, byte[] publicKey) {
        Pairing.HubSide offer = offers.get(serverName);
        if (offer == null || !offer.offer().offerId().equals(offerId)) return false;
        Optional<NetworkKey> key = offer.acceptReply(publicKey);
        if (key.isEmpty()) {
            if (offer.compromised()) {
                offers.remove(serverName);
                pendingKeys.remove(serverName);
                log.warn("Pairing for '" + serverName + "' got a second answer and was cancelled. Reconnect a player to start over.");
            }
            return false;
        }
        pendingKeys.put(serverName, key.get());
        log.warn("Pairing request from server '" + serverName + "': code " + offer.code().orElse("?")
                + ". If the server console shows the same code, run there: vnet pair accept " + offer.code().orElse("?"));
        return true;
    }

    /** Proxy console: pending pairings as "server code" lines. */
    public List<String> pendingPairings() {
        List<String> lines = new ArrayList<>();
        offers.forEach((name, offer) -> offer.code().filter(c -> pendingKeys.containsKey(name) && !offer.expired())
                .ifPresent(code -> lines.add(name + " " + code)));
        lines.sort(String::compareTo);
        return lines;
    }

    /** Proxy console {@code vnet unpair <server>}: forgets a stored pairing. */
    public boolean unpair(String serverName) {
        offers.remove(serverName);
        pendingKeys.remove(serverName);
        try {
            return Files.deleteIfExists(pairedKeyFile(serverName));
        } catch (IOException failure) {
            log.warn("Could not delete the key of '" + serverName + "': " + failure.getMessage());
            return false;
        }
    }

    /** Proxy console {@code vnet status}. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        HubServer s = server;
        lines.add(s == null ? "Hub not running" : "Hub on " + config().bindHost() + ":" + s.port() + " (servers connect to " + advertisedHost() + ")");
        lines.add("Keys: " + keySourceDescription());
        for (NetworkServerInfo info : node.state().servers()) {
            lines.add((info.connected() ? "  + " : "  - ") + info.name() + " · " + info.players() + " players"
                    + (info.connected() ? "" : " (Core not connected)"));
        }
        for (String pending : pendingPairings()) lines.add("  pairing waiting: " + pending);
        return lines;
    }

    /** Persists a pending pairing as soon as the server authenticated with it. */
    private final class PersistingHandler implements HubServer.Handler {
        @Override public void onConnect(String serverName) {
            NetworkKey pending = pendingKeys.remove(serverName);
            if (pending != null && !sharedKey()) {
                try {
                    KeyFile.save(pairedKeyFile(serverName), pending);
                    offers.remove(serverName);
                    log.info("Server '" + serverName + "' paired (key " + pending.fingerprint() + ")");
                } catch (IOException failure) {
                    log.warn("Could not save the key of '" + serverName + "': " + failure.getMessage());
                }
            }
            node.onConnect(serverName);
        }
        @Override public void onFrame(String serverName, Frame frame) { node.onFrame(serverName, frame); }
        @Override public void onDisconnect(String serverName) { node.onDisconnect(serverName); }
        @Override public boolean onPairReply(String serverName, String offerId, byte[] publicKey) {
            return node.onPairReply(serverName, offerId, publicKey);
        }
    }

    // ---- settings file ----

    private static Properties loadSettings(Path file, NetworkLog log) {
        Properties p = new Properties();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_SETTINGS, StandardCharsets.UTF_8);
            }
            try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not read " + file + " (" + failure.getMessage() + "); using defaults");
        }
        return p;
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException none) {
            return fallback;
        }
    }

    static final String DEFAULT_SETTINGS = """
            # Vystorm Core network hub (runs inside the proxy).
            # The servers learn this address automatically when a player joins them.
            enabled=true
            # Only 127.0.0.1 by default. Servers on other machines: set 0.0.0.0 (or the LAN address)
            # together with advertise-host and an allow list.
            bind-host=127.0.0.1
            port=25599
            # Address the servers use to reach this hub (default: bind-host, or 127.0.0.1 for 0.0.0.0).
            advertise-host=
            # Comma-separated IPs/CIDRs allowed to connect, e.g. 10.0.0.0/8,192.168.1.20 (empty = everyone who can reach the port).
            allow=
            # Name shown in pairing requests.
            hub-id=proxy
            # Optional fixed key (Base64, 32 bytes). Leave empty: Velocity modern forwarding or pairing is used.
            key=
            """;
}
