package Vystorm.vystorm_core.network.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servers and players of the network. The hub's copy is authoritative; participants mirror it from one
 * {@link #snapshot()} plus {@link FrameType#STATE_DELTA} frames. Thread-safe.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class NetworkState {
    private static final byte D_SERVER = 1, D_JOIN = 2, D_SWITCH = 3, D_LEAVE = 4;

    private record PlayerEntry(String name, String server) {}

    private final Map<String, Boolean> servers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerEntry> players = new ConcurrentHashMap<>();

    // ---- queries ----

    public List<NetworkServerInfo> servers() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlayerEntry p : players.values()) counts.merge(p.server(), 1, Integer::sum);
        List<NetworkServerInfo> list = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : servers.entrySet()) {
            list.add(new NetworkServerInfo(e.getKey(), e.getValue(), counts.getOrDefault(e.getKey(), 0)));
        }
        list.sort(Comparator.comparing(NetworkServerInfo::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Optional<String> serverOf(UUID player) {
        PlayerEntry p = players.get(player);
        return p == null ? Optional.empty() : Optional.of(p.server());
    }

    public Optional<UUID> playerByName(String name) {
        return players.entrySet().stream().filter(e -> e.getValue().name().equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst();
    }

    public int playerCount() { return players.size(); }

    // ---- hub-side mutations; each returns the delta frame to broadcast ----

    public Frame serverKnown(String server, boolean connected) {
        servers.put(server, connected);
        return delta(D_SERVER, out -> { out.writeUTF(server); out.writeBoolean(connected); });
    }

    public Frame playerJoined(UUID player, String name, String server) {
        players.put(player, new PlayerEntry(name, server));
        servers.putIfAbsent(server, false);
        return delta(D_JOIN, out -> { writeUuid(out, player); out.writeUTF(name); out.writeUTF(server); });
    }

    public Frame playerSwitched(UUID player, String server) {
        players.computeIfPresent(player, (id, p) -> new PlayerEntry(p.name(), server));
        servers.putIfAbsent(server, false);
        return delta(D_SWITCH, out -> { writeUuid(out, player); out.writeUTF(server); });
    }

    public Frame playerLeft(UUID player) {
        players.remove(player);
        return delta(D_LEAVE, out -> writeUuid(out, player));
    }

    // ---- replication ----

    public synchronized Frame snapshot() {
        return Frame.of(FrameType.STATE_SNAPSHOT, write(out -> {
            out.writeInt(servers.size());
            for (Map.Entry<String, Boolean> e : servers.entrySet()) { out.writeUTF(e.getKey()); out.writeBoolean(e.getValue()); }
            out.writeInt(players.size());
            for (Map.Entry<UUID, PlayerEntry> e : players.entrySet()) {
                writeUuid(out, e.getKey()); out.writeUTF(e.getValue().name()); out.writeUTF(e.getValue().server());
            }
        }));
    }

    /** Applies a snapshot or delta from the hub. Malformed frames are rejected without partial changes. */
    public synchronized void apply(Frame frame) throws FrameException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()));
            if (frame.type() == FrameType.STATE_SNAPSHOT) {
                Map<String, Boolean> newServers = new LinkedHashMap<>();
                Map<UUID, PlayerEntry> newPlayers = new LinkedHashMap<>();
                int s = in.readInt();
                if (s < 0 || s > 10_000) throw new FrameException("Bad server count");
                for (int i = 0; i < s; i++) newServers.put(in.readUTF(), in.readBoolean());
                int p = in.readInt();
                if (p < 0 || p > 1_000_000) throw new FrameException("Bad player count");
                for (int i = 0; i < p; i++) newPlayers.put(readUuid(in), new PlayerEntry(in.readUTF(), in.readUTF()));
                servers.clear(); servers.putAll(newServers);
                players.clear(); players.putAll(newPlayers);
                return;
            }
            if (frame.type() != FrameType.STATE_DELTA) throw new FrameException("Not a state frame");
            switch (in.readByte()) {
                case D_SERVER -> servers.put(in.readUTF(), in.readBoolean());
                case D_JOIN -> {
                    UUID id = readUuid(in); String name = in.readUTF(); String server = in.readUTF();
                    players.put(id, new PlayerEntry(name, server));
                    servers.putIfAbsent(server, false);
                }
                case D_SWITCH -> {
                    UUID id = readUuid(in); String server = in.readUTF();
                    players.computeIfPresent(id, (k, p) -> new PlayerEntry(p.name(), server));
                    servers.putIfAbsent(server, false);
                }
                case D_LEAVE -> players.remove(readUuid(in));
                default -> throw new FrameException("Unknown state delta");
            }
        } catch (FrameException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new FrameException("Malformed state frame", failure);
        }
    }

    /** Participant lost the hub: nothing is known any more. */
    public synchronized void clear() {
        servers.clear();
        players.clear();
    }

    // ---- helpers ----

    interface Writer { void write(DataOutputStream out) throws IOException; }

    private static Frame delta(byte kind, Writer body) {
        return Frame.of(FrameType.STATE_DELTA, write(out -> { out.writeByte(kind); body.write(out); }));
    }

    static byte[] write(Writer body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            body.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
