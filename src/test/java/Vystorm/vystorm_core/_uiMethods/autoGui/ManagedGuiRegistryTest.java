package Vystorm.vystorm_core._uiMethods.autoGui;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedGuiRegistryTest {
    private MockedStatic<Bukkit> bukkit;
    private ManagedGuiRegistry gui;
    private Plugin owner;
    private Player player;
    private Inventory inventory;
    private InventoryView view;
    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        owner = mock(Plugin.class); when(owner.isEnabled()).thenReturn(true); when(owner.getName()).thenReturn("Example"); when(owner.namespace()).thenReturn("example");
        player = mock(Player.class); when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class)); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        inventory = mock(Inventory.class); when(inventory.getViewers()).thenReturn(List.of(player));
        bukkit.when(() -> Bukkit.createInventory(null, 54, "Test")).thenReturn(inventory);
        view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(inventory);
        gui = new ManagedGuiRegistry();
    }
    @AfterEach void close() { bukkit.close(); }
    private void create(GuiCallbacks callbacks) { gui.create(owner, null, player.getUniqueId(), 54, "Test", callbacks); }
    private InventoryClickEvent click() {
        var e = mock(InventoryClickEvent.class); when(e.getWhoClicked()).thenReturn(player); when(e.getView()).thenReturn(view); return e;
    }
    @Test void givenCancelledClickThenDoesNotPerformBusinessAction() {
        var callback = mock(GuiCallbacks.class); create(callback);
        var e = click(); when(e.isCancelled()).thenReturn(true); gui.click(e);
        verifyNoInteractions(callback);
    }
    @Test void givenPersonalMenuThenRejectsAnotherPlayerBeforeCallback() {
        var callback = mock(GuiCallbacks.class); create(callback);
        var e = click(); Player other = mock(Player.class); when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(e.getWhoClicked()).thenReturn(other); gui.click(e);
        verify(e).setCancelled(true); verifyNoInteractions(callback);
    }
    @Test void givenDefaultMenuThenAllClickTypesAreCancelled() {
        create(new GuiCallbacks() {});
        for (ClickType type : ClickType.values()) {
            var e = click(); when(e.getClick()).thenReturn(type); gui.click(e); verify(e).setCancelled(true);
        }
    }
    @Test void givenDefaultMenuThenCrossInventoryDragIsCancelled() {
        create(new GuiCallbacks() {});
        var e = mock(InventoryDragEvent.class); when(e.getWhoClicked()).thenReturn(player); when(e.getView()).thenReturn(view);
        when(e.getRawSlots()).thenReturn(Set.of(4, 56)); gui.drag(e); verify(e).setCancelled(true);
    }
    @Test void givenCancelledDragThenNoTransferCallback() {
        var cb = mock(GuiCallbacks.class); create(cb);
        var e = mock(InventoryDragEvent.class); when(e.getWhoClicked()).thenReturn(player); when(e.getView()).thenReturn(view);
        when(e.isCancelled()).thenReturn(true); gui.drag(e); verifyNoInteractions(cb);
    }
    @Test void givenIntentionalTransferThenCallbackRunsOnceWithoutForcedCancellation() {
        var cb = mock(GuiCallbacks.class); create(cb); var e = click(); gui.click(e);
        verify(cb, times(1)).click(e); verify(e, never()).setCancelled(anyBoolean());
    }
    @Test void givenCallbackFailureThenNativeMoveRemainsCancelled() {
        create(new GuiCallbacks() { public void click(InventoryClickEvent e) { throw new IllegalStateException("test"); } });
        var e = click(); assertDoesNotThrow(() -> gui.click(e)); verify(e).setCancelled(true);
    }
    @Test void givenDisabledOwnerThenExistingMenuDoesNotExecute() {
        var cb = mock(GuiCallbacks.class); create(cb); when(owner.isEnabled()).thenReturn(false);
        var e = click(); gui.click(e); verify(e).setCancelled(true); verifyNoInteractions(cb);
    }
    @Test void givenClosedPersonalMenuThenReplayedEventHasNoAction() {
        var cb = mock(GuiCallbacks.class); create(cb);
        var e = mock(InventoryCloseEvent.class); when(e.getInventory()).thenReturn(inventory); when(e.getPlayer()).thenReturn(player);
        gui.close(e); gui.click(click()); assertFalse(gui.managed(inventory)); verify(cb).close(e); verify(cb, never()).click(any());
    }
    @Test void givenSharedLootThenOneViewerClosingKeepsOtherViewersBound() {
        var cb = mock(GuiCallbacks.class); gui.create(owner, null, null, 54, "Test", cb);
        var close = mock(InventoryCloseEvent.class); when(close.getInventory()).thenReturn(inventory); when(close.getPlayer()).thenReturn(player);
        gui.close(close); assertTrue(gui.managed(inventory)); verify(cb).close(close);
        gui.release(inventory); verify(player).closeInventory(); assertFalse(gui.managed(inventory));
    }
    @Test void givenPermissionsRevokedThenEntryCannotOpenAndDisappears() {
        @SuppressWarnings("unchecked") Consumer<Player> opener = mock(Consumer.class);
        when(player.hasPermission("use")).thenReturn(true);
        gui.register(owner, "menu", "Example", Material.CHEST, p -> p.hasPermission("use"), opener);
        assertEquals(List.of("example:menu"), gui.keys(player)); assertTrue(gui.open(player, "menu")); verify(opener).accept(player);
        when(player.hasPermission("use")).thenReturn(false);
        assertTrue(gui.keys(player).isEmpty()); assertFalse(gui.open(player, "example:menu")); verifyNoMoreInteractions(opener);
    }
    @Test void givenOwnerCleanupThenEntriesAndInventoriesAreReleased() {
        create(new GuiCallbacks() {}); gui.register(owner, "menu", "Example", Material.CHEST, p -> true, p -> {});
        gui.unregister(owner); assertFalse(gui.managed(inventory)); assertTrue(gui.keys(player).isEmpty()); verify(player).closeInventory();
    }
    @Test void givenAsyncCreationThenRejectBeforeBukkitInventoryMutation() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> create(new GuiCallbacks() {}));
        bukkit.verify(() -> Bukkit.createInventory(null, 54, "Test"), never());
    }
    @Test void givenDuplicateNamespaceThenDoesNotReplaceExistingOpener() {
        gui.register(owner, "menu", "Example", Material.CHEST, p -> true, p -> {});
        assertThrows(IllegalArgumentException.class, () -> gui.register(owner, "menu", "Other", Material.CHEST, p -> true, p -> {}));
    }
    @Test void givenGroupedTutorialThenOnlyCategoryIsInMainDirectoryAndBackReturnsThere() {
        var api = mock(Vystorm.vystorm_core.API.class);
        var dialogs = mock(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class);
        when(api.dialogs()).thenReturn(dialogs);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("guide")).thenReturn(true);
        gui.register(owner, "tutorials", "Tutorials", Material.BOOK, p -> true, p -> gui.directory(p, 0, "Tutorials"));
        gui.register(owner, "tutorial", "Example · Anleitung", Material.BOOK, p -> p.hasPermission("guide"), p -> {}, "Tutorials");
        try (var core = mockStatic(Vystorm.vystorm_core.main.class);
             var bodies = mockStatic(Vystorm.vystorm_core.compat.dialog.DialogBody.class)) {
            core.when(Vystorm.vystorm_core.main::getApi).thenReturn(api);
            bodies.when(() -> Vystorm.vystorm_core.compat.dialog.DialogBody.plainMessage(any()))
                    .thenReturn(mock(Vystorm.vystorm_core.compat.dialog.DialogBody.class));
            var requests = org.mockito.ArgumentCaptor.forClass(Vystorm.vystorm_core.compat.dialog.DialogRequest.class);
            gui.directory(player, 0);
            verify(dialogs).open(any(), eq(player), requests.capture());
            assertEquals(2, requests.getValue().buttons().size());
            assertEquals(net.kyori.adventure.text.Component.text("Tutorials"), requests.getValue().buttons().getLast().label());
            requests.getValue().buttons().getLast().action().accept(player, null);
            verify(dialogs, times(2)).open(any(), eq(player), requests.capture());
            assertEquals(2, requests.getValue().buttons().size());
            assertEquals(net.kyori.adventure.text.Component.text("Example · Anleitung"), requests.getValue().buttons().getFirst().label());
            assertTrue(gui.open(player, "example:tutorial")); // Existing direct links still work.
            requests.getValue().buttons().getLast().action().accept(player, null);
            verify(dialogs, times(3)).open(any(), eq(player), requests.capture());
            assertEquals(2, requests.getValue().buttons().size());
            when(player.hasPermission("guide")).thenReturn(false);
            gui.directory(player, 0, "Tutorials");
            verify(dialogs, times(4)).open(any(), eq(player), requests.capture());
            assertEquals(1, requests.getValue().buttons().size()); // Only the back button remains.
            assertFalse(gui.open(player, "example:tutorial"));
            gui.unregister(owner);
            assertTrue(gui.keys(player).isEmpty());
        }
    }
    @Test void givenRejectedOpenThenDiscardsPersonalContextAfterFinalOpenResult() {
        var callback = mock(GuiCallbacks.class); create(callback);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(a -> { tasks.add(a.getArgument(1)); return null; });
        var e = mock(InventoryOpenEvent.class); when(e.getInventory()).thenReturn(inventory); when(e.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        gui.openResult(e); assertTrue(gui.managed(inventory)); tasks.getFirst().run();
        assertFalse(gui.managed(inventory)); verify(callback).discarded(inventory);
        verify(callback, never()).close(any());
    }
    @Test void givenSuccessfulOpenThenKeepsPersonalSessionAfterFinalOpenResult() {
        var callback = mock(GuiCallbacks.class); create(callback);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        var tasks = new ArrayList<Runnable>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(a -> { tasks.add(a.getArgument(1)); return null; });
        var e = mock(InventoryOpenEvent.class); when(e.getInventory()).thenReturn(inventory); when(e.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        gui.openResult(e); tasks.getFirst().run(); assertTrue(gui.managed(inventory)); verifyNoInteractions(callback);
    }

    @Test void givenDisabledOwnerWhenEntriesListedThenHidden() {
        gui.register(owner, "shop", "Shop", Material.EMERALD, p -> true, p -> { }, "Wirtschaft", "Kaufen");
        assertEquals(List.of("example:shop"), gui.entries(player, "Wirtschaft").stream().map(MenuEntryView::key).toList());
        when(owner.isEnabled()).thenReturn(false);
        assertTrue(gui.entries(player, "Wirtschaft").isEmpty());
    }

    @Test void givenRequiredPermissionRevokedAfterRenderThenOpenRechecksPermissionAndPredicate() {
        @SuppressWarnings("unchecked") Consumer<Player> opener = mock(Consumer.class);
        when(player.hasPermission("example.menu.use")).thenReturn(true);
        when(player.hasPermission("provider.condition")).thenReturn(true);
        gui.register(owner, "secure", "Secure", Material.CHEST,
                p -> p.hasPermission("provider.condition"), opener);
        gui.requiredPermission(owner, "secure", "example.menu.use");

        assertEquals(List.of("example:secure"), gui.keys(player));
        assertTrue(gui.open(player, "example:secure"));
        when(player.hasPermission("example.menu.use")).thenReturn(false);

        assertTrue(gui.entries(player).isEmpty());
        assertEquals(Vystorm.vystorm_core.lang.Localization.get().core().text(player,
                "menu.access.missing-permission", "permission", "example.menu.use"),
                gui.inspect(player).getFirst().reason());
        assertFalse(gui.open(player, "example:secure"));
        verify(opener, times(1)).accept(player);
    }

    @Test void givenPermissionAndPredicateFailuresOrDisabledOwnerThenInspectDistinguishesReasons() {
        gui.register(owner, "permission", "Permission", Material.CHEST, p -> true, p -> { });
        gui.requiredPermission(owner, "permission", "example.permission");
        gui.register(owner, "predicate", "Predicate", Material.CHEST, p -> false, p -> { });
        Plugin disabled = mock(Plugin.class);
        when(disabled.isEnabled()).thenReturn(false);
        when(disabled.getName()).thenReturn("Disabled");
        when(disabled.namespace()).thenReturn("disabled");
        gui.register(disabled, "menu", "Disabled", Material.CHEST, p -> true, p -> { });

        List<MenuAccess> access = gui.inspect(player);
        assertEquals(Vystorm.vystorm_core.lang.Localization.get().core().text(player,
                "menu.access.missing-permission", "permission", "example.permission"), access.get(0).reason());
        assertEquals("{lang:vystorm_core:menu.access.denied}", access.get(1).reason());
        assertEquals("{lang:vystorm_core:menu.access.disabled}", access.get(2).reason());
        assertFalse(access.get(0).allowed());
        assertFalse(access.get(1).allowed());
        assertFalse(access.get(2).allowed());
    }

    @Test void givenOwnerMismatchOrMalformedPermissionThenRejectsDeclarationAndCleanupRemovesIt() {
        Plugin otherOwner = mock(Plugin.class);
        when(otherOwner.namespace()).thenReturn("example");
        gui.register(owner, "managed", "Managed", Material.CHEST, p -> true, p -> { });

        assertThrows(IllegalArgumentException.class,
                () -> gui.requiredPermission(otherOwner, "managed", "example.use"));
        assertThrows(IllegalArgumentException.class, () -> gui.requiredPermission(owner, "managed", "  "));
        assertThrows(IllegalArgumentException.class, () -> gui.requiredPermission(owner, "managed", "x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> gui.requiredPermission(owner, "missing", "example.use"));

        gui.requiredPermission(owner, "managed", "example.use");
        assertFalse(gui.unregister(otherOwner, "managed")); // Another plugin cannot unregister it.
        assertTrue(gui.unregister(owner, "managed"));
        gui.register(owner, "managed", "Managed", Material.CHEST, p -> true, p -> { });
        assertTrue(gui.open(player, "example:managed")); // Required permission metadata was removed.

        gui.requiredPermission(owner, "managed", "example.use");
        gui.unregister(owner);
        gui.register(owner, "managed", "Managed", Material.CHEST, p -> true, p -> { });
        assertTrue(gui.open(player, "example:managed")); // Plugin-wide cleanup also removes declarations.

        gui.requiredPermission(owner, "managed", "example.use");
        gui.clear();
        gui.register(owner, "managed", "Managed", Material.CHEST, p -> true, p -> { });
        assertTrue(gui.open(player, "example:managed")); // Global registry clear also removes declarations.
    }

    @Test void givenProviderRuntimeAndLinkageFailuresThenAccessAndReasonStayFailClosed() {
        gui.register(owner, "broken", "Broken", Material.CHEST, p -> { throw new LinkageError("provider gone"); }, p -> { });
        gui.register(owner, "runtime", "Runtime", Material.CHEST, p -> { throw new IllegalStateException("provider failed"); }, p -> { });
        gui.register(owner, "denied", "Denied", Material.CHEST, p -> false, p -> { });
        gui.reason(owner, "denied", p -> { throw new LinkageError("reason provider gone"); });

        assertDoesNotThrow(() -> gui.keys(player));
        assertDoesNotThrow(() -> gui.entries(player));
        assertFalse(gui.open(player, "example:broken"));
        assertFalse(gui.open(player, "example:runtime"));
        List<MenuAccess> access = assertDoesNotThrow(() -> gui.inspect(player));
        assertEquals("{lang:vystorm_core:menu.access.provider-failed}", access.get(0).reason());
        assertEquals("{lang:vystorm_core:menu.access.provider-failed}", access.get(1).reason());
        assertEquals("{lang:vystorm_core:menu.access.denied}", access.get(2).reason());
        assertTrue(gui.unregister(owner, "broken"));
        gui.clear();
        assertTrue(gui.inspect(player).isEmpty());
    }
}
