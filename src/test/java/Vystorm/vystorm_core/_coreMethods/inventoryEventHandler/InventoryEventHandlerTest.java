package Vystorm.vystorm_core._coreMethods.inventoryEventHandler;

import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.singeltons.InventoryEventSingelton;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InventoryEventHandlerTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<main> pluginMain;
    private final List<Runnable> tasks = new ArrayList<>();
    private InventoryEventHandler handler;
    private Plugin core;
    private Plugin owner;
    private Player player;
    private org.bukkit.inventory.PlayerInventory playerInventory;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        pluginMain = mockStatic(main.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return null;
        });
        core = plugin("Core"); owner = plugin("Owner");
        pluginMain.when(main::getPlugin).thenReturn(core);
        player = mock(Player.class); when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        playerInventory = mock(org.bukkit.inventory.PlayerInventory.class); when(player.getInventory()).thenReturn(playerInventory);
        handler = new InventoryEventHandler();
    }

    @AfterEach void close() { pluginMain.close(); bukkit.close(); }

    @Test void givenRepeatedSignalsThenCoalescesOneCallbackAndOneDrain() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        handler.watchPlayers(owner, callback);
        handler.changed(player); handler.changed(player);
        verifyScheduledOnce(); tasks.getFirst().run();
        verify(callback).accept(player);
    }

    @Test void givenLateCancellationThenSkipsDeferredCallback() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        boolean[] allowed = {true}; handler.watchPlayers(owner, callback);
        handler.changed(player, () -> allowed[0]); allowed[0] = false;
        tasks.getFirst().run(); verifyNoInteractions(callback);
    }

    @Test void givenSecondValidNotificationThenDeliversOnce() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        handler.watchPlayers(owner, callback);
        handler.changed(player, () -> false); handler.changed(player, () -> true);
        tasks.getFirst().run(); verify(callback).accept(player);
    }

    @Test void givenOwnerCleanupThenRemovesPendingAndSubscriptions() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        handler.watchPlayers(owner, callback); handler.changed(player); handler.removeAll(owner);
        tasks.getFirst().run(); verifyNoInteractions(callback);
    }

    @Test void givenReentrantCallbackThenSchedulesFollowingTick() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        doAnswer(ignored -> { handler.changed(player); return null; }).when(callback).accept(player);
        handler.watchPlayers(owner, callback); handler.changed(player);
        tasks.getFirst().run(); verify(callback).accept(player); verifyScheduledTwice();
        tasks.get(1).run(); verify(callback, times(2)).accept(player);
    }

    @Test void givenOfflinePlayerOrAsyncMutationThenSkipsAndRejects() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        handler.watchPlayers(owner, callback); handler.changed(player); when(player.isOnline()).thenReturn(false);
        tasks.getFirst().run(); verifyNoInteractions(callback);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> handler.changed(player));
    }

    @Test void givenFailingCallbackThenOtherSubscribersStillReceiveNotification() {
        @SuppressWarnings("unchecked") Consumer<Player> failing = mock(Consumer.class);
        @SuppressWarnings("unchecked") Consumer<Player> succeeding = mock(Consumer.class);
        doThrow(new IllegalStateException("expected")).when(failing).accept(player);
        handler.watchPlayers(owner, failing); handler.watchPlayers(plugin("Other"), succeeding);
        handler.changed(player); tasks.getFirst().run();
        verify(succeeding).accept(player);
    }

    @Test void givenInventoryIdentityThenOnlyMatchingInstanceReceivesNotification() {
        Inventory first = mock(Inventory.class); Inventory second = mock(Inventory.class);
        @SuppressWarnings("unchecked") Consumer<Inventory> callback = mock(Consumer.class);
        handler.watchInventories(owner, inventory -> inventory == first, callback);
        handler.changed(second); assertEqualsNoTasks(); handler.changed(first); tasks.getFirst().run();
        verify(callback).accept(first);
    }

    @Test void givenHandlerReplacementThenOldPendingNotificationsCannotRun() {
        @SuppressWarnings("unchecked") Consumer<Player> callback = mock(Consumer.class);
        InventoryEventSingelton singleton = new InventoryEventSingelton();
        InventoryEventInterface old = singleton.getInstance();
        old.watchPlayers(owner, callback); old.changed(player);
        InventoryEventInterface replacement = mock(InventoryEventInterface.class);
        singleton.override(replacement); tasks.getFirst().run();
        verifyNoInteractions(callback);
    }

    @Test void givenSameHandlerReplacementThenDoesNotClearIt() {
        InventoryEventSingelton singleton = new InventoryEventSingelton();
        InventoryEventInterface replacement = mock(InventoryEventInterface.class);
        singleton.override(replacement); singleton.override(replacement);
        verify(replacement, never()).clear();
    }

    @Test void givenCancelledUseThenDoesNotNotifyUseWatch() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        handler.watchUses(owner, callback);
        handler.observeUse(player, 0, mock(org.bukkit.inventory.ItemStack.class), () -> false);
        verifyNoInteractions(callback);
    }

    @Test void givenUseThenExposesDefensivePreUseStack() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        org.bukkit.inventory.ItemStack original = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.inventory.ItemStack stored = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.inventory.ItemStack exposed = mock(org.bukkit.inventory.ItemStack.class);
        when(original.clone()).thenReturn(stored); when(stored.clone()).thenReturn(exposed);
        handler.watchUses(owner, callback); handler.observeUse(player, 40, original, () -> true);
        org.mockito.ArgumentCaptor<InventoryUse> uses = org.mockito.ArgumentCaptor.forClass(InventoryUse.class);
        verify(callback).accept(uses.capture());
        assertEquals(40, uses.getValue().slot()); assertEquals(true, uses.getValue().valid());
        org.junit.jupiter.api.Assertions.assertNotSame(original, uses.getValue().item());
        org.junit.jupiter.api.Assertions.assertSame(exposed, uses.getValue().item());
    }

    @Test void givenUseInvalidatedDuringFinalGuardThenObservedUseIsInvalid() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        handler.watchUses(owner, callback);
        handler.observeUse(player, 0, mock(org.bukkit.inventory.ItemStack.class), () -> {
            handler.invalidateUses(player); return true;
        });
        org.mockito.ArgumentCaptor<InventoryUse> uses = org.mockito.ArgumentCaptor.forClass(InventoryUse.class);
        verify(callback).accept(uses.capture()); assertEquals(false, uses.getValue().valid());
    }

    @Test void givenDisabledOrRemovedUseOwnerThenDoesNotNotify() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        handler.watchUses(owner, callback); when(owner.isEnabled()).thenReturn(false);
        handler.observeUse(player, 0, mock(org.bukkit.inventory.ItemStack.class), () -> true);
        verifyNoInteractions(callback);
        when(owner.isEnabled()).thenReturn(true); handler.removeAll(owner);
        handler.observeUse(player, 0, mock(org.bukkit.inventory.ItemStack.class), () -> true);
        verifyNoInteractions(callback);
    }

    @Test void givenNativeBoneMealStatisticAndOneItemDecreaseThenEmitsUse() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        org.bukkit.inventory.ItemStack original = stack(2), current = mock(org.bukkit.inventory.ItemStack.class);
        when(current.isSimilar(any())).thenReturn(true); when(current.getAmount()).thenReturn(1);
        when(playerInventory.getItem(0)).thenReturn(current);
        bukkit.when(Bukkit::getCurrentTick).thenReturn(7);
        handler.watchUses(owner, callback); handler.captureUseIntent(player, 0, original, () -> true); handler.confirmBoneMealUse(player);
        verify(callback).accept(any(InventoryUse.class));
    }

    @Test void givenNativeBoneMealStatisticAndLastItemRemovedThenEmitsUse() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        bukkit.when(Bukkit::getCurrentTick).thenReturn(7); when(playerInventory.getItem(0)).thenReturn(null);
        handler.watchUses(owner, callback); handler.captureUseIntent(player, 0, stack(1), () -> true); handler.confirmBoneMealUse(player);
        verify(callback).accept(any(InventoryUse.class));
    }

    @Test void givenWrongCountVariantStaleMissingOrInvalidatedIntentThenDoesNotEmit() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        handler.watchUses(owner, callback); bukkit.when(Bukkit::getCurrentTick).thenReturn(7);
        org.bukkit.inventory.ItemStack current = mock(org.bukkit.inventory.ItemStack.class);
        when(current.isSimilar(any())).thenReturn(true); when(current.getAmount()).thenReturn(0);
        when(playerInventory.getItem(0)).thenReturn(current);
        handler.captureUseIntent(player, 0, stack(2), () -> true); handler.confirmBoneMealUse(player); // count must be one lower
        when(current.getAmount()).thenReturn(2);
        handler.captureUseIntent(player, 0, stack(2), () -> true); handler.confirmBoneMealUse(player); // unchanged count
        when(current.isSimilar(any())).thenReturn(false); when(current.getAmount()).thenReturn(1);
        handler.captureUseIntent(player, 0, stack(2), () -> true); handler.confirmBoneMealUse(player); // wrong variant
        handler.confirmBoneMealUse(player); // missing candidate
        handler.captureUseIntent(player, 0, stack(2), () -> true); bukkit.when(Bukkit::getCurrentTick).thenReturn(8); handler.confirmBoneMealUse(player); // stale
        bukkit.when(Bukkit::getCurrentTick).thenReturn(9); handler.captureUseIntent(player, 0, stack(2), () -> true); handler.invalidateUses(player); handler.confirmBoneMealUse(player);
        verifyNoInteractions(callback);
    }

    @Test void givenCancelledStatisticThenStillConfirmsNativeConsumption() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        bukkit.when(Bukkit::getCurrentTick).thenReturn(7); when(playerInventory.getItem(0)).thenReturn(null);
        handler.watchUses(owner, callback); handler.captureUseIntent(player, 0, stack(1), () -> true);
        handler.confirmBoneMealUse(player); // Listener deliberately does not filter cancelled statistic events.
        verify(callback).accept(any(InventoryUse.class));
    }

    @Test void givenCapturedUseThenLateGuardLifecycleOrOwnerChangesMakeItInvalid() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        boolean[] allowed = {true}; handler.watchUses(owner, callback);
        handler.observeUse(player, 0, stack(1), () -> allowed[0]);
        org.mockito.ArgumentCaptor<InventoryUse> uses = org.mockito.ArgumentCaptor.forClass(InventoryUse.class);
        verify(callback).accept(uses.capture()); allowed[0] = false; assertEquals(false, uses.getValue().valid());

        allowed[0] = true; handler.observeUse(player, 0, stack(1), () -> allowed[0]);
        verify(callback, times(2)).accept(uses.capture()); handler.invalidateUses(player); assertEquals(false, uses.getAllValues().get(1).valid());

        handler.observeUse(player, 0, stack(1), () -> true); verify(callback, times(3)).accept(uses.capture());
        handler.removeAll(owner); assertEquals(false, uses.getAllValues().get(2).valid());
    }

    @Test void givenHandlerOverrideThenPreviouslyObservedUseBecomesInvalid() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        InventoryEventSingelton singleton = new InventoryEventSingelton();
        InventoryEventInterface old = singleton.getInstance(); old.watchUses(owner, callback); old.observeUse(player, 0, stack(1), () -> true);
        org.mockito.ArgumentCaptor<InventoryUse> uses = org.mockito.ArgumentCaptor.forClass(InventoryUse.class);
        verify(callback).accept(uses.capture()); singleton.override(mock(InventoryEventInterface.class));
        assertEquals(false, uses.getValue().valid());
    }

    @Test void givenForgetUsePlayerThenPreviouslyObservedUseStaysInvalidEvenWhileOnline() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        handler.watchUses(owner, callback); handler.observeUse(player, 0, stack(1), () -> true);
        org.mockito.ArgumentCaptor<InventoryUse> uses = org.mockito.ArgumentCaptor.forClass(InventoryUse.class);
        verify(callback).accept(uses.capture()); when(player.isOnline()).thenReturn(true);
        handler.forgetUsePlayer(player); assertEquals(false, uses.getValue().valid());
    }

    @Test void givenNewInteractionThenOldBoneMealCandidateCannotMatchLaterStatistic() {
        @SuppressWarnings("unchecked") Consumer<InventoryUse> callback = mock(Consumer.class);
        bukkit.when(Bukkit::getCurrentTick).thenReturn(7); when(playerInventory.getItem(0)).thenReturn(null);
        handler.watchUses(owner, callback); handler.captureUseIntent(player, 0, stack(1), () -> true);
        handler.discardUseIntent(player); handler.confirmBoneMealUse(player);
        verifyNoInteractions(callback);
    }

    private org.bukkit.inventory.ItemStack stack(int amount) {
        org.bukkit.inventory.ItemStack original = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.inventory.ItemStack stored = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.inventory.ItemStack delivered = mock(org.bukkit.inventory.ItemStack.class);
        when(original.clone()).thenReturn(stored); when(stored.clone()).thenReturn(delivered); when(stored.getAmount()).thenReturn(amount);
        return original;
    }

    private Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getName()).thenReturn(name); return plugin;
    }
    private void verifyScheduledOnce() { org.junit.jupiter.api.Assertions.assertEquals(1, tasks.size()); }
    private void verifyScheduledTwice() { org.junit.jupiter.api.Assertions.assertEquals(2, tasks.size()); }
    private void assertEqualsNoTasks() { org.junit.jupiter.api.Assertions.assertEquals(0, tasks.size()); }
}
