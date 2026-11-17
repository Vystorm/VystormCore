package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.network.NetworkServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServerSelectorModelTest {
    private static ServerSelectorModel.Options options(Set<String> hidden) {
        return new ServerSelectorModel.Options() {
            @Override public String display(String server) { return "<white>" + server; }
            @Override public String icon(String server) { return server.equals("survival") ? "grass_block" : null; }
            @Override public boolean hidden(String server) { return hidden.contains(server); }
        };
    }

    private static final List<NetworkServer> SERVERS = List.of(
            new NetworkServer("lobby", true, 3), new NetworkServer("survival", true, 10),
            new NetworkServer("creative", false, 0), new NetworkServer("staff", true, 1));

    @Test
    void givenHiddenServerThenNotListed() {
        var entries = ServerSelectorModel.entries(SERVERS, "lobby", options(Set.of("staff")));
        assertEquals(List.of("lobby", "survival", "creative"), entries.stream().map(ServerSelectorModel.Entry::server).toList());
    }

    @Test
    void givenOwnServerThenMarkedCurrentAndNotClickable() {
        var lobby = ServerSelectorModel.entries(SERVERS, "lobby", options(Set.of())).get(0);
        assertEquals(ServerSelectorModel.State.CURRENT, lobby.state());
        assertFalse(lobby.clickable());
    }

    @Test
    void givenServerWithoutCoreThenOfflineAndNotClickable() {
        var creative = ServerSelectorModel.entries(SERVERS, "lobby", options(Set.of())).get(2);
        assertEquals(ServerSelectorModel.State.OFFLINE, creative.state());
        assertFalse(creative.clickable());
    }

    @Test
    void givenOtherConnectedServerThenClickableWithConfiguredIcon() {
        var survival = ServerSelectorModel.entries(SERVERS, "lobby", options(Set.of())).get(1);
        assertTrue(survival.clickable());
        assertEquals("GRASS_BLOCK", survival.icon());
        assertEquals(10, survival.players());
        assertEquals("COMPASS", ServerSelectorModel.entries(SERVERS, "lobby", options(Set.of())).get(0).icon());
    }

    @Test
    void givenNoServersThenEmpty() {
        assertTrue(ServerSelectorModel.entries(List.of(), "", options(Set.of())).isEmpty());
    }
}
