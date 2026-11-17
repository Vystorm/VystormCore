package Vystorm.vystorm_core.item;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.storage.VersionedStore;
import Vystorm.vystorm_core.worlds.ManagedWorlds;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Durable, idempotently credited rewards, claimed outside managed visits. Server thread only.
 * One instance per owner/namespace; local-backend storage, not a cross-server inventory transaction.
 * Claims require room for the entire next reward; overflow stays in the pouch, never on the ground.
 * A prepared before/after inventory and a player-data receipt reconcile interrupted deliveries.
 * Conflicting inventory changes stop recovery instead of overwriting the player's items.
 * Callers must serialize access to player inventories on the server thread. Vanilla player saveData
 * and Core file storage must both remain intact; this is not a guarantee against disk loss.
 * <p>Preferred access: {@code api.loot().pouches()}.
 *
 * @since 0.32.0
 */
public final class RewardPouch {
    public enum ClaimResult { DELIVERED, EMPTY, FULL, BUSY, RECOVERY_REQUIRED }
    public static final class Document {
        public Map<String, String> rewards = new LinkedHashMap<>();
        public Set<String> credited = new HashSet<>();
        public Delivery delivery;
    }
    public record Delivery(String id, String rewardId, String before, String after) {}
    private final Plugin owner;
    private final API core;
    private final String namespace;
    private final NamespacedKey receipt;
    private final Map<UUID, VersionedStore<Document>> stores = new HashMap<>();
    private final Set<UUID> uncertain = new HashSet<>();

    private RewardPouch(Plugin owner, API core, String namespace) {
        this.owner = Objects.requireNonNull(owner); this.core = Objects.requireNonNull(core);
        if (namespace == null || !namespace.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid pouch namespace");
        this.namespace = namespace;
        receipt = new NamespacedKey(owner, "reward_receipt_" + namespace);
    }
    public static RewardPouch open(Plugin owner, API core, String namespace) throws Exception {
        requireThread();
        return new RewardPouch(owner, core, namespace);
    }
    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Reward pouch requires the server thread");
    }
    private VersionedStore<Document> store(UUID player) {
        requireThread();
        if (!owner.isEnabled()) throw new IllegalStateException("Pouch owner is disabled");
        return stores.computeIfAbsent(player, id -> {
            try {
                return VersionedStore.open(owner, core, "rewards/" + namespace, id + ".yml", Document.class, 1, Map.of(), Document::new);
            } catch (Exception failure) { throw new IllegalStateException("Cannot open reward pouch " + id, failure); }
        });
    }
    private Document read(UUID player) {
        Document state = store(player).load();
        if (state.rewards == null || state.credited == null || !state.credited.containsAll(state.rewards.keySet()))
            throw new IllegalStateException("Invalid reward ledger; recovery required");
        if (state.delivery != null && !state.rewards.containsKey(state.delivery.rewardId()))
            throw new IllegalStateException("Invalid pending delivery; recovery required");
        return state;
    }
    /** Persist a unique source receipt before reporting success; retry the same ID after an ambiguous failure. */
    public boolean credit(UUID player, String claimId, ItemStack... items) {
        Objects.requireNonNull(player);
        if (claimId == null || claimId.isBlank() || claimId.length() > 200) throw new IllegalArgumentException("Invalid claim ID");
        if (items == null || items.length == 0 || items.length > 36) throw new IllegalArgumentException("1..36 reward stacks required");
        for (ItemStack item : items) if (item == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(item) || item.getAmount() > item.getMaxStackSize())
            throw new IllegalArgumentException("Invalid reward stack");
        Document state = read(player);
        if (state.credited.contains(claimId)) return false;
        if (state.rewards.size() >= 1024 || state.credited.size() >= 100_000) throw new IllegalStateException("Pouch capacity reached");
        state.rewards.put(claimId, encode(items)); state.credited.add(claimId);
        store(player).save(state);
        return true;
    }
    /** False while an interrupted inventory delivery must be reconciled before another temporary inventory. */
    public boolean readyForVisit(UUID player) {
        Document state = read(player);
        return state.delivery == null && !uncertain.contains(player);
    }
    public int pending(UUID player) { return read(player).rewards.size(); }

    /** Claims one complete reward. An ambiguous save failure blocks this instance until reconnect/reload. */
    public ClaimResult claim(Player player) {
        requireThread();
        UUID id = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || ManagedWorlds.visits().inVisit(id) || ManagedWorlds.visits().hasSnapshot(id))
            return ClaimResult.BUSY;
        if (uncertain.contains(id)) return ClaimResult.RECOVERY_REQUIRED;
        Document state = read(id);
        if (state.delivery != null) return deliver(player, state);
        if (state.rewards.isEmpty()) return ClaimResult.EMPTY;
        Map.Entry<String, String> reward = state.rewards.entrySet().iterator().next();
        ItemStack[] before = player.getInventory().getStorageContents();
        ItemStack[] after = fit(before, decode(reward.getValue()));
        if (after == null) return ClaimResult.FULL;
        state.delivery = new Delivery(UUID.randomUUID().toString(), reward.getKey(), encode(before), encode(after));
        store(id).save(state); // no player mutation before the write succeeds
        return deliver(player, state);
    }
    private ClaimResult deliver(Player player, Document state) {
        UUID id = player.getUniqueId();
        Delivery delivery = state.delivery;
        String applied = player.getPersistentDataContainer().get(receipt, PersistentDataType.STRING);
        if (!delivery.id().equals(applied)) {
            if (!sameInventory(player.getInventory().getStorageContents(), decode(delivery.before()))) return ClaimResult.RECOVERY_REQUIRED;
            // Both values are serialized in the same player-data save. Never clear the receipt before ledger commit.
            player.getInventory().setStorageContents(decode(delivery.after()));
            player.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, delivery.id());
        }
        try { player.saveData(); }
        catch (RuntimeException failure) { uncertain.add(id); throw failure; }
        state.rewards.remove(delivery.rewardId()); state.delivery = null;
        store(id).save(state);
        return ClaimResult.DELIVERED;
    }
    /** Clear the in-memory ambiguous-write barrier only after Bukkit has loaded fresh player data on join. */
    public void reconnected(Player player) { requireThread(); uncertain.remove(player.getUniqueId()); }

    static boolean sameInventory(ItemStack[] actual, ItemStack[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            boolean emptyActual = actual[i] == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(actual[i]);
            boolean emptyExpected = expected[i] == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(expected[i]);
            if (emptyActual || emptyExpected) { if (emptyActual != emptyExpected) return false; }
            else if (!actual[i].equals(expected[i])) return false;
        }
        return true;
    }

    static ItemStack[] fit(ItemStack[] contents, ItemStack[] rewards) {
        ItemStack[] result = Arrays.stream(contents).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
        for (ItemStack reward : rewards) {
            if (reward == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(reward)) continue;
            int left = reward.getAmount();
            for (ItemStack slot : result) {
                if (slot == null || !slot.isSimilar(reward)) continue;
                int add = Math.min(left, Math.max(0, slot.getMaxStackSize() - slot.getAmount()));
                slot.setAmount(slot.getAmount() + add); left -= add;
            }
            for (int i = 0; i < result.length && left > 0; i++) if (result[i] == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(result[i])) {
                int add = Math.min(left, reward.getMaxStackSize());
                result[i] = reward.clone(); result[i].setAmount(add); left -= add;
            }
            if (left != 0) return null;
        }
        return result;
    }
    private static String encode(ItemStack[] items) { return Base64.getEncoder().encodeToString(Vystorm.vystorm_core.compat.ItemCompat.serialize(items)); }
    private static ItemStack[] decode(String items) { return Vystorm.vystorm_core.compat.ItemCompat.deserialize(Base64.getDecoder().decode(items)); }
}
