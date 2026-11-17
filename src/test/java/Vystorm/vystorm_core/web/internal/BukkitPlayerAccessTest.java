package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Berechtigungs-Schnappschüsse: Offline-Spieler dürfen nie über die Bukkit-Standardwerte mehr bekommen als online. */
class BukkitPlayerAccessTest {
    private static final String NODE = "vystorm.shop.web";

    @Test
    void givenNodeDeniedWhileOnline_whenPlayerQuitsBeforeRedeemingLink_thenOfflineDefaultDoesNotGrantIt() {
        UUID id = UUID.randomUUID();
        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(id);
        when(online.hasPermission(NODE)).thenReturn(false); // z. B. LuckPerms-Negation eines default-true-Knotens
        when(online.hasPermission(WebDispatcher.USE_PERMISSION)).thenReturn(true);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.isOp()).thenReturn(false);
        when(offline.isBanned()).thenReturn(false);
        PluginManager pm = mock(PluginManager.class);
        when(pm.getPermission(NODE)).thenReturn(new Permission(NODE, PermissionDefault.TRUE));
        AtomicReference<Player> current = new AtomicReference<>(online);
        SessionStore sessions = new SessionStore(System::currentTimeMillis, 60_000, 1_800_000, 43_200_000, 5);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenAnswer(i -> current.get());
            bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(offline);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> current.get() == null ? List.of() : List.of(current.get()));
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            BukkitPlayerAccess access = new BukkitPlayerAccess(mock(Plugin.class), () -> Set.of(NODE, WebDispatcher.USE_PERMISSION),
                    sessions::playersWithAccess, banned -> { });

            // /web im Spiel: Link wird ausgestellt, solange der Spieler online ist.
            sessions.issueLoginToken(id, "Steve", null);
            access.capture(id);
            // Spieler verlässt das Spiel und löst den Link erst danach im Browser ein.
            current.set(null);
            access.refresh();
            sessions.createSession(id, "Steve");
            access.refresh();

            assertFalse(access.has(id, NODE), "last online value (denied) must win over the offline default (true)");
            assertTrue(access.has(id, WebDispatcher.USE_PERMISSION));
        }
    }

    @Test
    void givenOpenLoginToken_whenRefreshRuns_thenTokenHolderGetsLiveSnapshot() {
        UUID id = UUID.randomUUID();
        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(id);
        when(online.hasPermission(any(String.class))).thenReturn(false);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        PluginManager pm = mock(PluginManager.class);
        when(pm.getPermission(NODE)).thenReturn(new Permission(NODE, PermissionDefault.TRUE));
        AtomicReference<Player> current = new AtomicReference<>(online);
        SessionStore sessions = new SessionStore(System::currentTimeMillis, 60_000, 1_800_000, 43_200_000, 5);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenAnswer(i -> current.get());
            bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(offline);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i -> current.get() == null ? List.of() : List.of(current.get()));
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            BukkitPlayerAccess access = new BukkitPlayerAccess(mock(Plugin.class), () -> Set.of(NODE), sessions::playersWithAccess, banned -> { });
            sessions.issueLoginToken(id, "Alex", null);
            access.refresh(); // ohne capture(): der Refresh im nächsten Tick erfasst Token-Inhaber
            current.set(null);
            access.refresh();
            assertFalse(access.has(id, NODE));
        }
    }
}
