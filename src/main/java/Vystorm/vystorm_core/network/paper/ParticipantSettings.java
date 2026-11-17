package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.network.core.ChannelMessage;
import Vystorm.vystorm_core.network.core.KeyFile;
import Vystorm.vystorm_core.network.core.KeySources;
import Vystorm.vystorm_core.network.core.NetworkKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Where a participant gets its key, name and hub address (Bukkit-free so it can be unit-tested).
 * Key order (spec): manual {@code network.key} → Velocity secret (only with {@code proxies.velocity.enabled}) →
 * paired key file. Name and address: config values win, otherwise the identity the proxy announced, which is
 * remembered in {@code network/hub.properties} so an empty server reconnects after a restart.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ParticipantSettings {
    public enum Mode { AUTO, ON, OFF }

    private final Mode mode;
    private final String manualKey;
    private final String configName;
    private final String configHost;
    private final int configPort;
    private final Path paperGlobal;
    private final Path dir;
    private volatile Properties identity;

    public ParticipantSettings(String enabled, String manualKey, String configName, String configHost, int configPort,
                               Path paperGlobal, Path networkDir) {
        this.mode = switch (enabled == null ? "auto" : enabled.trim().toLowerCase(Locale.ROOT)) {
            case "false", "off", "no" -> Mode.OFF;
            case "true", "on", "yes" -> Mode.ON;
            default -> Mode.AUTO;
        };
        this.manualKey = manualKey == null ? "" : manualKey;
        this.configName = configName == null ? "" : configName.trim();
        this.configHost = configHost == null ? "" : configHost.trim();
        this.configPort = configPort;
        this.paperGlobal = paperGlobal;
        this.dir = networkDir;
        this.identity = loadIdentity();
    }

    public Mode mode() { return mode; }

    public Path keyFile() { return dir.resolve("network.key"); }

    public Optional<NetworkKey> key() {
        Optional<NetworkKey> manual = KeySources.fromBase64(manualKey);
        if (manual.isPresent()) return manual;
        Optional<NetworkKey> velocity = KeySources.readPaperVelocitySecret(paperGlobal).map(KeySources::fromVelocitySecret);
        if (velocity.isPresent()) return velocity;
        return KeyFile.load(keyFile());
    }

    /** Where the key came from, for {@code /vnet status}. */
    public String keySource() {
        if (KeySources.fromBase64(manualKey).isPresent()) return "config";
        if (KeySources.readPaperVelocitySecret(paperGlobal).isPresent()) return "velocity";
        if (KeyFile.load(keyFile()).isPresent()) return "paired";
        return "none";
    }

    public Optional<String> serverName() {
        if (!configName.isEmpty()) return Optional.of(configName);
        String remembered = identity.getProperty("server-name", "").trim();
        return remembered.isEmpty() ? Optional.empty() : Optional.of(remembered);
    }

    public Optional<InetSocketAddress> hubAddress() {
        String host = !configHost.isEmpty() ? configHost : identity.getProperty("hub-host", "").trim();
        int port = configPort > 0 ? configPort : parsePort(identity.getProperty("hub-port", ""));
        if (host.isEmpty() || port <= 0) return Optional.empty();
        return Optional.of(new InetSocketAddress(host, port));
    }

    /**
     * Takes the identity the proxy announced. Only fills what the config leaves open, and the caller only calls this
     * while not connected, so a connected server can never be redirected.
     *
     * @return true if something changed
     */
    public synchronized boolean learn(ChannelMessage.Identity announced) throws IOException {
        Properties next = new Properties();
        next.putAll(identity);
        next.setProperty("server-name", announced.serverName());
        next.setProperty("hub-host", announced.hubHost());
        next.setProperty("hub-port", Integer.toString(announced.hubPort()));
        if (next.equals(identity)) return false;
        saveIdentity(next);
        identity = next;
        return true;
    }

    /** Remembers the hub of a confirmed pairing. */
    public synchronized void rememberPairedHub(String serverName, String host, int port) throws IOException {
        learn(new ChannelMessage.Identity(serverName, host, port));
    }

    private Properties loadIdentity() {
        Properties p = new Properties();
        Path file = dir.resolve("hub.properties");
        if (!Files.isRegularFile(file)) return p;
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | RuntimeException unreadable) {
            // treated as unknown; never overwritten until a new identity arrives
        }
        return p;
    }

    private void saveIdentity(Properties next) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("hub.properties");
        Path temp = Files.createTempFile(dir, ".hub", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { next.store(out, "Vystorm network: learned from the proxy"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException noAtomic) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int parsePort(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException none) { return -1; }
    }
}
