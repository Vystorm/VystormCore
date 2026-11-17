package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.network.NetworkServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * What the server selector shows (Bukkit-free, unit-tested). Hidden servers are left out.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ServerSelectorModel {
    public enum State { CURRENT, AVAILABLE, OFFLINE }

    /** Per-server settings from {@code network.servers.<name>}. */
    public interface Options {
        String display(String server);   // MiniMessage, default "<white>name"
        String icon(String server);      // material name, default COMPASS
        boolean hidden(String server);
    }

    public record Entry(String server, String display, String icon, int players, State state) {
        public boolean clickable() { return state == State.AVAILABLE; }
    }

    private ServerSelectorModel() {}

    public static List<Entry> entries(Collection<NetworkServer> servers, String current, Options options) {
        List<Entry> list = new ArrayList<>();
        for (NetworkServer server : servers) {
            if (options.hidden(server.name())) continue;
            State state = server.name().equals(current) ? State.CURRENT
                    : server.connected() ? State.AVAILABLE : State.OFFLINE;
            String icon = options.icon(server.name());
            list.add(new Entry(server.name(), options.display(server.name()),
                    icon == null || icon.isBlank() ? "COMPASS" : icon.trim().toUpperCase(Locale.ROOT), server.players(), state));
        }
        return list;
    }
}
