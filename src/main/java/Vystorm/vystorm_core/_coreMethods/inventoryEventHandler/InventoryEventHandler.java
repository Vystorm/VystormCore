package Vystorm.vystorm_core._coreMethods.inventoryEventHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Default coalesced notification implementation; all mutations belong to the primary server thread. */
public final class InventoryEventHandler implements InventoryEventInterface {
    private record PlayerWatch(Plugin owner, Consumer<Player> callback) {}
    private record InventoryWatch(Plugin owner, Predicate<Inventory> accepts, Consumer<Inventory> callback) {}
    private record UseWatch(Plugin owner, Consumer<InventoryUse> callback) {}
    private record UseIntent(int tick, int slot, org.bukkit.inventory.ItemStack original, BooleanSupplier valid,
                             long epoch, long generation) {}

    private final List<PlayerWatch> playerWatches = new ArrayList<>();
    private final List<InventoryWatch> inventoryWatches = new ArrayList<>();
    private final List<UseWatch> useWatches = new ArrayList<>();
    private final Map<PlayerWatch, IdentityHashMap<Player, List<BooleanSupplier>>> pendingPlayers = new IdentityHashMap<>();
    private final Map<InventoryWatch, IdentityHashMap<Inventory, List<BooleanSupplier>>> pendingInventories = new IdentityHashMap<>();
    private boolean drainScheduled;
    private final Map<Player, Long> useEpochs = new WeakHashMap<>();
    private final IdentityHashMap<Player, UseIntent> useIntents = new IdentityHashMap<>();
    private long useGeneration;
    private boolean intentCleanupScheduled;

    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory event operations require the server thread");
    }

    @Override public void watchPlayers(Plugin owner, Consumer<Player> callback) {
        thread();
        playerWatches.add(new PlayerWatch(checkedOwner(owner), Objects.requireNonNull(callback, "callback")));
    }

    @Override public void watchInventories(Plugin owner, Predicate<Inventory> accepts, Consumer<Inventory> callback) {
        thread();
        inventoryWatches.add(new InventoryWatch(checkedOwner(owner), Objects.requireNonNull(accepts, "accepts"),
                Objects.requireNonNull(callback, "callback")));
    }

    @Override public void watchUses(Plugin owner, Consumer<InventoryUse> callback) {
        thread();
        useWatches.add(new UseWatch(checkedOwner(owner), Objects.requireNonNull(callback, "callback")));
    }

    @Override public void observeUse(Player player, int slot, org.bukkit.inventory.ItemStack original, BooleanSupplier valid) {
        thread(); Objects.requireNonNull(player, "player"); Objects.requireNonNull(original, "original"); Objects.requireNonNull(valid, "valid");
        if (slot < 0 || (slot > 8 && slot != 40)) throw new IllegalArgumentException("slot must be 0..8 or 40");
        long epoch = useEpochs.getOrDefault(player, 0L), generation = useGeneration;
        boolean permitted;
        try { permitted = valid.getAsBoolean(); }
        catch (RuntimeException failure) { report("inventory use validity check", null, failure); return; }
        if (!permitted) return;
        for (UseWatch watch : List.copyOf(useWatches)) {
            if (!contains(useWatches, watch) || !watch.owner().isEnabled()) continue;
            InventoryUse use = new InventoryUse(player, slot, original, () -> player.isOnline()
                    && contains(useWatches, watch) && watch.owner().isEnabled()
                    && useGeneration == generation && useEpochs.getOrDefault(player, 0L) == epoch && validUse(valid, watch.owner()));
            invoke("inventory use callback", watch.owner(), () -> watch.callback().accept(use));
        }
    }

    @Override public void invalidateUses(Player player) {
        thread(); Objects.requireNonNull(player, "player");
        useEpochs.merge(player, 1L, Long::sum);
        useIntents.remove(player);
    }

    @Override public void forgetUsePlayer(Player player) {
        thread(); Objects.requireNonNull(player, "player");
        invalidateUses(player);
    }

    @Override public void discardUseIntent(Player player) {
        thread(); Objects.requireNonNull(player, "player");
        useIntents.remove(player);
    }

    @Override public void captureUseIntent(Player player, int slot, org.bukkit.inventory.ItemStack original, BooleanSupplier valid) {
        thread(); Objects.requireNonNull(player, "player"); Objects.requireNonNull(original, "original"); Objects.requireNonNull(valid, "valid");
        if (slot < 0 || (slot > 8 && slot != 40)) throw new IllegalArgumentException("slot must be 0..8 or 40");
        useIntents.put(player, new UseIntent(Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick(), slot, original.clone(), valid,
                useEpochs.getOrDefault(player, 0L), useGeneration));
        if (!intentCleanupScheduled) {
            intentCleanupScheduled = true;
            Bukkit.getScheduler().runTask(main.getPlugin(), this::clearStaleUseIntents);
        }
    }

    @Override public void confirmBoneMealUse(Player player) {
        thread(); Objects.requireNonNull(player, "player");
        UseIntent intent = useIntents.remove(player);
        if (intent == null || intent.tick() != Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick()
                || intent.generation() != useGeneration || intent.epoch() != useEpochs.getOrDefault(player, 0L)
                || !validIntent(intent, player) || !decreasedByOne(player, intent)) return;
        observeUse(player, intent.slot(), intent.original(), () -> intent.generation() == useGeneration
                && intent.epoch() == useEpochs.getOrDefault(player, 0L) && validIntent(intent, player));
    }

    @Override public void changed(Player player) { changed(player, () -> true); }

    @Override public void changed(Player player, BooleanSupplier valid) {
        thread(); Objects.requireNonNull(player, "player"); Objects.requireNonNull(valid, "valid");
        boolean queued = false;
        for (PlayerWatch watch : List.copyOf(playerWatches)) {
            if (!watch.owner().isEnabled()) continue;
            pendingPlayers.computeIfAbsent(watch, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(player, ignored -> new ArrayList<>()).add(valid);
            queued = true;
        }
        if (queued) scheduleDrain();
    }

    @Override public void changed(Inventory inventory) { changed(inventory, () -> true); }

    @Override public void changed(Inventory inventory, BooleanSupplier valid) {
        thread(); Objects.requireNonNull(inventory, "inventory"); Objects.requireNonNull(valid, "valid");
        boolean queued = false;
        for (InventoryWatch watch : List.copyOf(inventoryWatches)) {
            if (!watch.owner().isEnabled() || !accepts(watch, inventory)) continue;
            pendingInventories.computeIfAbsent(watch, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(inventory, ignored -> new ArrayList<>()).add(valid);
            queued = true;
        }
        if (queued) scheduleDrain();
    }

    @Override public void removeAll(Plugin owner) {
        thread(); Objects.requireNonNull(owner, "owner");
        playerWatches.removeIf(watch -> watch.owner() == owner);
        inventoryWatches.removeIf(watch -> watch.owner() == owner);
        pendingPlayers.keySet().removeIf(watch -> watch.owner() == owner);
        pendingInventories.keySet().removeIf(watch -> watch.owner() == owner);
        useWatches.removeIf(watch -> watch.owner() == owner);
    }

    @Override public void clear() {
        thread();
        playerWatches.clear(); inventoryWatches.clear(); useWatches.clear(); pendingPlayers.clear(); pendingInventories.clear();
        useEpochs.clear(); useGeneration++;
        useIntents.clear();
    }

    private Plugin checkedOwner(Plugin owner) {
        owner = Objects.requireNonNull(owner, "owner");
        if (!owner.isEnabled()) throw new IllegalStateException("Inventory event owner is disabled");
        return owner;
    }

    private boolean accepts(InventoryWatch watch, Inventory inventory) {
        try { return watch.accepts().test(inventory); }
        catch (RuntimeException failure) {
            report("inventory predicate", watch.owner(), failure);
            return false;
        }
    }

    private boolean validIntent(UseIntent intent, Player player) {
        try { return intent.valid().getAsBoolean(); }
        catch (RuntimeException failure) { report("bone-meal use validity check", null, failure); return false; }
    }

    private boolean validUse(BooleanSupplier valid, Plugin owner) {
        try { return valid.getAsBoolean(); }
        catch (RuntimeException failure) { report("inventory use validity check", owner, failure); return false; }
    }

    private boolean decreasedByOne(Player player, UseIntent intent) {
        org.bukkit.inventory.ItemStack current = player.getInventory().getItem(intent.slot());
        if (intent.original().getAmount() == 1) return current == null || current.getType().isAir();
        return current != null && current.isSimilar(intent.original()) && current.getAmount() == intent.original().getAmount() - 1;
    }

    private void clearStaleUseIntents() {
        thread(); intentCleanupScheduled = false;
        int tick = Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick();
        useIntents.entrySet().removeIf(entry -> entry.getValue().tick() < tick);
    }

    private void scheduleDrain() {
        if (drainScheduled) return;
        drainScheduled = true;
        Bukkit.getScheduler().runTask(main.getPlugin(), this::drain);
    }

    private void drain() {
        thread();
        drainScheduled = false;
        Map<PlayerWatch, IdentityHashMap<Player, List<BooleanSupplier>>> players = new IdentityHashMap<>(pendingPlayers);
        Map<InventoryWatch, IdentityHashMap<Inventory, List<BooleanSupplier>>> inventories = new IdentityHashMap<>(pendingInventories);
        pendingPlayers.clear(); pendingInventories.clear();
        players.forEach((watch, changed) -> changed.forEach((player, signals) -> {
            if (contains(playerWatches, watch) && watch.owner().isEnabled() && player.isOnline() && valid(signals)) {
                invoke("player callback", watch.owner(), () -> watch.callback().accept(player));
            }
        }));
        inventories.forEach((watch, changed) -> changed.forEach((inventory, signals) -> {
            if (contains(inventoryWatches, watch) && watch.owner().isEnabled() && valid(signals)) {
                invoke("inventory callback", watch.owner(), () -> watch.callback().accept(inventory));
            }
        }));
    }

    private boolean valid(List<BooleanSupplier> signals) {
        for (BooleanSupplier signal : signals) {
            try { if (signal.getAsBoolean()) return true; }
            catch (RuntimeException failure) { report("inventory validity check", null, failure); }
        }
        return false;
    }

    private static <T> boolean contains(List<T> values, T expected) {
        return values.stream().anyMatch(value -> value == expected);
    }

    private void invoke(String operation, Plugin owner, Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException failure) { report(operation, owner, failure); }
    }

    private void report(String operation, Plugin owner, RuntimeException failure) {
        String name = owner == null ? "unknown" : owner.getName();
        DebugMessages.high("[InventoryEventHandler] " + operation + " failed; owner=" + name
                + "; error=" + failure.getClass().getSimpleName() + "; message=" + failure.getMessage());
    }
}
