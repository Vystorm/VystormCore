package Vystorm.vystorm_core.playermenu.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import Vystorm.vystorm_core.playermenu.internal.PlayerMenuTrigger.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerMenuTriggerTest {
    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration config;
    private PlayerMenuRegistry registry;
    private PlayerMenuTrigger trigger;
    private final AtomicLong now = new AtomicLong(10_000);
    private final List<Player> opened = new ArrayList<>();
    private boolean optedIn = true;
    private Player viewer, target;
    private ItemStack hand;

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        config = new YamlConfiguration();
        Plugin core = mock(Plugin.class);
        when(core.getConfig()).thenReturn(config);
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Party");
        when(owner.isEnabled()).thenReturn(true);
        registry = new PlayerMenuRegistry();
        registry.register(owner, PlayerMenuEntry.builder("invite").icon((v, t) -> item(Material.CAKE))
                .visibleWhen((v, t) -> !t.getName().equals("Hidden")).onClick((v, t, c) -> PlayerMenu.Result.CLOSE).build());
        trigger = new PlayerMenuTrigger(core, registry, p -> optedIn, now::get, (v, t, entries) -> { opened.add(t); return true; });
        viewer = player("Viewer");
        target = player("Target");
        hand = item(Material.AIR);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenAnswer(i -> hand);
        when(viewer.getInventory()).thenReturn(inventory);
        when(viewer.isSneaking()).thenReturn(true);
        when(viewer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(viewer.hasPermission(PlayerMenu.PERMISSION)).thenReturn(true);
    }

    @AfterEach void tearDown() { bukkit.close(); }

    @Test void givenAllConditions_whenSneakRightClickingAPlayer_thenMenuOpensAndEventIsCancelled() {
        PlayerInteractEntityEvent event = click(target, EquipmentSlot.HAND);

        assertEquals(Decision.OPEN, trigger.handle(event));
        assertTrue(event.isCancelled());
        assertEquals(List.of(target), opened);
    }

    @Test void givenOffHand_whenClicking_thenIgnored() { assertIgnored(click(target, EquipmentSlot.OFF_HAND), Decision.OFF_HAND); }

    @Test void givenNonPlayerEntity_whenClicking_thenIgnored() { assertIgnored(click(mock(Cow.class), EquipmentSlot.HAND), Decision.NOT_A_PLAYER); }

    @Test void givenFeatureDisabled_whenClicking_thenIgnored() {
        config.set("player-menu.enabled", false);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.DISABLED);
    }

    @Test void givenCitizensNpc_whenClicking_thenIgnored() {
        when(target.hasMetadata("NPC")).thenReturn(true);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.NPC);
    }

    @Test void givenPlayerEntityThatIsNotOnline_whenClicking_thenTreatedAsNpc() {
        Player fake = mock(Player.class);
        when(fake.getUniqueId()).thenReturn(UUID.randomUUID());
        when(fake.getName()).thenReturn("Fake");
        assertIgnored(click(fake, EquipmentSlot.HAND), Decision.NPC);
    }

    @Test void givenNotSneaking_whenClicking_thenIgnoredUnlessSneakIsOptional() {
        when(viewer.isSneaking()).thenReturn(false);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.NOT_SNEAKING);

        config.set("player-menu.require-sneak", false);
        assertEquals(Decision.OPEN, trigger.handle(click(target, EquipmentSlot.HAND)));
    }

    @Test void givenItemInMainHand_whenClicking_thenIgnored() {
        hand = item(Material.LEAD);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.HAND_NOT_EMPTY);
    }

    @Test void givenSpectator_whenClicking_thenIgnored() {
        when(viewer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.SPECTATOR);
    }

    @Test void givenNoPermission_whenClicking_thenIgnored() {
        when(viewer.hasPermission(PlayerMenu.PERMISSION)).thenReturn(false);
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.NO_PERMISSION);
    }

    @Test void givenPlayerSwitchedItOff_whenClicking_thenIgnored() {
        optedIn = false;
        assertIgnored(click(target, EquipmentSlot.HAND), Decision.OPTED_OUT);
    }

    @Test void givenNoVisibleEntry_whenClicking_thenVanillaInteractionIsKept() {
        Player hidden = player("Hidden");
        assertIgnored(click(hidden, EquipmentSlot.HAND), Decision.NO_ENTRIES);
    }

    @Test void givenDoubleClick_whenWithinCooldown_thenOpensOnceAndOpensAgainLater() {
        assertEquals(Decision.OPEN, trigger.handle(click(target, EquipmentSlot.HAND)));
        now.addAndGet(PlayerMenuTrigger.COOLDOWN_MILLIS - 1);
        assertEquals(Decision.COOLDOWN, trigger.handle(click(target, EquipmentSlot.HAND)));
        now.addAndGet(1);
        assertEquals(Decision.OPEN, trigger.handle(click(target, EquipmentSlot.HAND)));
        assertEquals(2, opened.size());
    }

    @Test void givenSelf_whenClicking_thenIgnored() { assertIgnored(click(viewer, EquipmentSlot.HAND), Decision.SELF); }

    private void assertIgnored(PlayerInteractEntityEvent event, Decision expected) {
        assertEquals(expected, trigger.handle(event));
        assertFalse(event.isCancelled());
        assertTrue(opened.isEmpty());
    }

    private PlayerInteractEntityEvent click(Entity clicked, EquipmentSlot hand) {
        return new PlayerInteractEntityEvent(viewer, clicked, hand);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        return player;
    }

    private static ItemStack item(Material type) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.isEmpty()).thenReturn(type == Material.AIR);
        return stack;
    }
}
