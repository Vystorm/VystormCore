package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.storage.VersionedStore;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import Vystorm.vystorm_core.worlds.VisitService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RewardPouchTest {
    MockedStatic<Bukkit> bukkit;
    MockedStatic<ManagedWorlds> worlds;
    MockedStatic<VersionedStore> factory;
    MockedStatic<ItemStack> itemCodec;
    Player player; PlayerInventory inventory; VisitService visits;
    Plugin owner; API api; RewardPouch pouch;
    ItemStack[] contents; ItemStack diamond;
    String persisted; boolean failWrite;
    final Map<String, ItemStack[]> encoded = new LinkedHashMap<>();
    final Map<NamespacedKey, String> receipts = new HashMap<>();
    final UUID id = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        worlds = mockStatic(ManagedWorlds.class); visits = mock(VisitService.class); worlds.when(ManagedWorlds::visits).thenReturn(visits);
        owner = mock(Plugin.class); when(owner.getName()).thenReturn("TestRewards"); when(owner.namespace()).thenReturn("testrewards"); when(owner.isEnabled()).thenReturn(true);
        api = mock(API.class);
        VersionedStore<RewardPouch.Document> store = new VersionedStore<>(new VersionedStore.Backend() {
            public String read() { return persisted; }
            public void write(String value) { if (failWrite) throw new IllegalStateException("disk unavailable"); persisted = value; }
        }, RewardPouch.Document.class, 1, Map.of(), RewardPouch.Document::new, null);
        factory = mockStatic(VersionedStore.class);
        factory.when(() -> VersionedStore.open(eq(owner), eq(api), anyString(), anyString(), eq(RewardPouch.Document.class), eq(1), anyMap(), any())).thenReturn(store);
        itemCodec = mockStatic(ItemStack.class);
        itemCodec.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack[] array = call.getArgument(0);
            for (var entry : encoded.entrySet()) if (Arrays.equals(entry.getValue(), array)) return entry.getKey().getBytes(StandardCharsets.UTF_8);
            String key = "items-" + encoded.size(); encoded.put(key, array.clone()); return key.getBytes(StandardCharsets.UTF_8);
        });
        itemCodec.when(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenAnswer(call -> encoded.get(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)).clone());
        diamond = stack(1);
        player = mock(Player.class); inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(player.getInventory()).thenReturn(inventory);
        contents = new ItemStack[36];
        when(inventory.getStorageContents()).thenAnswer(call -> contents.clone());
        doAnswer(call -> { contents = ((ItemStack[]) call.getArgument(0)).clone(); return null; }).when(inventory).setStorageContents(any());
        PersistentDataContainer pdc = mock(PersistentDataContainer.class); when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> receipts.get(call.getArgument(0)));
        doAnswer(call -> { receipts.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        pouch = RewardPouch.open(owner, api, "dream");
    }
    ItemStack stack(int amount) {
        ItemStack item = mock(ItemStack.class); int[] count = {amount};
        when(item.getAmount()).thenAnswer(call -> count[0]); when(item.getMaxStackSize()).thenReturn(64);
        doAnswer(call -> { count[0] = call.getArgument(0); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> stack(count[0])); return item;
    }
    @AfterEach void cleanup() { itemCodec.close(); factory.close(); worlds.close(); bukkit.close(); }
    @Test void serializedAirAndNullBothRepresentEmptySlots() {
        ItemStack air = mock(ItemStack.class); when(air.isEmpty()).thenReturn(true);
        assertTrue(RewardPouch.sameInventory(new ItemStack[]{null, diamond}, new ItemStack[]{air, diamond}));
        assertFalse(RewardPouch.sameInventory(new ItemStack[]{null, diamond}, new ItemStack[]{diamond, diamond}));
    }
    @Test void duplicateCreditSurvivesReopenAndClaim() throws Exception {
        assertTrue(pouch.credit(id, "run:cloud", diamond));
        pouch = RewardPouch.open(owner, api, "dream");
        assertFalse(pouch.credit(id, "run:cloud", diamond));
        assertEquals(RewardPouch.ClaimResult.DELIVERED, pouch.claim(player));
        assertEquals(0, pouch.pending(id)); assertFalse(pouch.credit(id, "run:cloud", diamond));
        assertEquals(RewardPouch.ClaimResult.EMPTY, pouch.claim(player));
    }
    @Test void fullInventoryKeepsWholeRewardAndDoesNotMutatePlayer() {
        Arrays.fill(contents, stack(64)); pouch.credit(id, "run", diamond);
        assertEquals(RewardPouch.ClaimResult.FULL, pouch.claim(player)); assertEquals(1, pouch.pending(id));
        verify(inventory, never()).setStorageContents(any()); verify(player, never()).saveData();
    }
    @Test void prepareWriteFailureNeverTouchesInventory() {
        pouch.credit(id, "run", diamond); failWrite = true;
        assertThrows(IllegalStateException.class, () -> pouch.claim(player));
        verify(inventory, never()).setStorageContents(any());
        failWrite = false; assertEquals(1, pouch.pending(id));
    }
    @Test void failedCreditCanRetrySameReceipt() {
        pouch.pending(id); failWrite = true;
        assertThrows(IllegalStateException.class, () -> pouch.credit(id, "run", diamond));
        failWrite = false; assertTrue(pouch.credit(id, "run", diamond)); assertEquals(1, pouch.pending(id));
    }
    @Test void receiptRecoversAfterLedgerCommitFailureWithoutGivingAgain() throws Exception {
        pouch.credit(id, "run", diamond);
        doAnswer(call -> { failWrite = true; return null; }).when(player).saveData();
        assertThrows(IllegalStateException.class, () -> pouch.claim(player));
        failWrite = false; doNothing().when(player).saveData();
        pouch = RewardPouch.open(owner, api, "dream");
        assertEquals(RewardPouch.ClaimResult.DELIVERED, pouch.claim(player));
        verify(inventory, times(1)).setStorageContents(any()); assertEquals(0, pouch.pending(id));
    }
    @Test void ambiguousPlayerSaveRetainsPreparedRewardAndBlocksRetry() {
        pouch.credit(id, "run", diamond); doThrow(new IllegalStateException("save failed")).when(player).saveData();
        assertThrows(IllegalStateException.class, () -> pouch.claim(player));
        assertEquals(1, pouch.pending(id)); assertFalse(pouch.readyForVisit(id));
        assertEquals(RewardPouch.ClaimResult.RECOVERY_REQUIRED, pouch.claim(player));
    }
    @Test void crashBeforePlayerSaveReplaysOnlyMatchingOriginalInventory() throws Exception {
        pouch.credit(id, "run", diamond); doThrow(new IllegalStateException("crash")).when(player).saveData();
        assertThrows(IllegalStateException.class, () -> pouch.claim(player));
        receipts.clear(); contents = new ItemStack[36]; doNothing().when(player).saveData();
        pouch = RewardPouch.open(owner, api, "dream");
        assertEquals(RewardPouch.ClaimResult.DELIVERED, pouch.claim(player)); assertEquals(0, pouch.pending(id));
    }
    @Test void conflictingInventoryNeverOverwrittenDuringRecovery() throws Exception {
        pouch.credit(id, "run", diamond); doThrow(new IllegalStateException("crash")).when(player).saveData();
        assertThrows(IllegalStateException.class, () -> pouch.claim(player));
        receipts.clear(); contents = new ItemStack[36]; contents[5] = stack(17);
        pouch = RewardPouch.open(owner, api, "dream");
        assertEquals(RewardPouch.ClaimResult.RECOVERY_REQUIRED, pouch.claim(player));
        verify(inventory, times(1)).setStorageContents(any()); assertEquals(1, pouch.pending(id));
    }
    @Test void unRestoredVisitBlocksClaims() {
        pouch.credit(id, "run", diamond); when(visits.hasSnapshot(id)).thenReturn(true);
        assertEquals(RewardPouch.ClaimResult.BUSY, pouch.claim(player)); verify(player, never()).saveData();
    }
}
