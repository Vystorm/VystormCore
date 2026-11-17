package Vystorm.vystorm_core._integrations.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultVaultTest {
    @Test void givenSameNamedProviderReplacementWhenQuotingThenIdentityChangesButSnapshotStaysStable() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginManager plugins = mock(PluginManager.class);
            ServicesManager services = mock(ServicesManager.class);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getName()).thenReturn("Owner");
            when(plugins.getPlugin("Vault")).thenReturn(plugin);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            Economy first = mock(Economy.class), second = mock(Economy.class);
            for (Economy provider : new Economy[]{first, second}) {
                when(provider.isEnabled()).thenReturn(true);
                when(provider.getName()).thenReturn("SameName");
            }
            when(services.getRegistration(Economy.class)).thenReturn(
                    new RegisteredServiceProvider<>(Economy.class, first, ServicePriority.Normal, plugin));
            DefaultVault live = new DefaultVault();
            VaultInterface snapshot = live.economySnapshot();
            String identity = live.economyIdentity();
            assertEquals("Owner:SameName:" + System.identityHashCode(first), identity);
            assertEquals(identity, snapshot.economyIdentity());
            assertEquals(identity, live.economySnapshot().economyIdentity());
            when(services.getRegistration(Economy.class)).thenReturn(
                    new RegisteredServiceProvider<>(Economy.class, second, ServicePriority.Normal, plugin));
            assertNotEquals(identity, live.economyIdentity());
            assertEquals(identity, snapshot.economyIdentity());
        }
    }
    @Test
    void givenSnapshotWhenProviderChangesThenRefundUsesOriginalProvider() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginManager plugins = mock(PluginManager.class);
            ServicesManager services = mock(ServicesManager.class);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugins.getPlugin("Vault")).thenReturn(plugin);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            Economy first = mock(Economy.class), second = mock(Economy.class);
            when(first.isEnabled()).thenReturn(true);
            when(second.isEnabled()).thenReturn(true);
            when(first.getName()).thenReturn("first");
            when(second.getName()).thenReturn("second");
            when(first.fractionalDigits()).thenReturn(0);
            when(second.fractionalDigits()).thenReturn(3);
            when(services.getRegistration(Economy.class)).thenReturn(
                    new RegisteredServiceProvider<>(Economy.class, first, ServicePriority.Normal, plugin));
            DefaultVault live = new DefaultVault();
            VaultInterface snapshot = live.economySnapshot();
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(first.withdrawPlayer(player, 10)).thenReturn(new EconomyResponse(
                    10, 20, EconomyResponse.ResponseType.SUCCESS, null));
            when(first.depositPlayer(player, 10)).thenReturn(new EconomyResponse(
                    10, 30, EconomyResponse.ResponseType.SUCCESS, null));
            assertTrue(snapshot.withdraw(player, 10).successful());
            when(services.getRegistration(Economy.class)).thenReturn(
                    new RegisteredServiceProvider<>(Economy.class, second, ServicePriority.Normal, plugin));
            assertEquals("second", live.economyName());
            assertEquals(3, live.fractionalDigits());
            assertEquals("first", snapshot.economyName());
            assertEquals(0, snapshot.fractionalDigits());
            assertTrue(snapshot.deposit(player, 10).successful());
            verify(second, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
            when(first.isEnabled()).thenReturn(false);
            assertFalse(snapshot.isEconomyAvailable());
            assertFalse(snapshot.deposit(player, 10).successful());
            verify(first, times(1)).depositPlayer(player, 10);
            verify(second, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        }
    }

    @Test
    void givenMissingProviderWhenSnapshotRequestedThenReject() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            assertThrows(IllegalStateException.class, () -> new DefaultVault().economySnapshot());
            assertThrows(IllegalStateException.class, () -> new DefaultVault().fractionalDigits());
        }
    }

    @Test
    void givenMissingVaultWhenQueriedThenRemainOptional() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginManager plugins = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            DefaultVault vault = new DefaultVault();
            assertFalse(vault.isEconomyAvailable());
            assertFalse(vault.deposit(mock(OfflinePlayer.class), 2).successful());
            assertNull(DefaultVault.getChat());
            assertNull(DefaultVault.getPermissions());
            assertThrows(IllegalStateException.class, vault::economyName);
        }
    }

    @Test
    void givenProviderReplacementWhenPayingThenUseCurrentProviderAndReturnFailure() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginManager plugins = mock(PluginManager.class);
            ServicesManager services = mock(ServicesManager.class);
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugins.getPlugin("Vault")).thenReturn(plugin);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            Economy first = mock(Economy.class);
            Economy second = mock(Economy.class);
            when(first.isEnabled()).thenReturn(true);
            when(second.isEnabled()).thenReturn(true);
            when(first.getName()).thenReturn("first");
            when(second.getName()).thenReturn("second");
            when(services.getRegistration(Economy.class)).thenReturn(
                    new RegisteredServiceProvider<>(Economy.class, first, ServicePriority.Normal, plugin),
                    new RegisteredServiceProvider<>(Economy.class, second, ServicePriority.Normal, plugin));
            DefaultVault vault = new DefaultVault();
            assertEquals("first", vault.economyName());
            assertEquals("second", vault.economyName());
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(second.withdrawPlayer(player, 10)).thenReturn(new EconomyResponse(
                    0, 5, EconomyResponse.ResponseType.FAILURE, "Insufficient funds"));
            var result = vault.withdraw(player, 10);
            assertFalse(result.successful());
            assertEquals(5, result.balance());
            assertEquals("Insufficient funds", result.error());
            verify(first, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
            assertNull(DefaultVault.getChat());
        }
    }

    @Test
    void givenInvalidAmountsWhenPayingThenNeverContactProvider() {
        DefaultVault vault = new DefaultVault();
        OfflinePlayer player = mock(OfflinePlayer.class);
        for (double amount : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> vault.deposit(player, amount));
            assertThrows(IllegalArgumentException.class, () -> vault.withdraw(player, amount));
        }
    }
}
