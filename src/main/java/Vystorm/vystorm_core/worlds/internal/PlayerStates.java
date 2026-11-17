package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.VisitPolicy;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Erfassen, Vorbereiten und Zurückspielen des Spielerzustands (Server-Thread). */
final class PlayerStates {
    private PlayerStates() {}

    static boolean empty(ItemStack stack) { return stack == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(stack); }

    /** Offenes Handwerksfeld (eigenes 2×2 oder Werkbank), sonst {@code null}. */
    static CraftingInventory crafting(Player player) {
        try {
            var view = player.getOpenInventory();
            Inventory top = view == null ? null : Vystorm.vystorm_core.compat.InventoryCompat.top(view);
            return top instanceof CraftingInventory crafting ? crafting : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Read-only check for items introduced into cursor/crafting after closing at entry. */
    static List<ItemStack> transientItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        if (!empty(player.getItemOnCursor())) items.add(player.getItemOnCursor());
        CraftingInventory crafting = crafting(player);
        if (crafting != null) for (ItemStack stack : crafting.getMatrix()) if (!empty(stack)) items.add(stack);
        return items;
    }

    /** Capture saved state; extras are retained for legacy snapshot compatibility. */
    static VisitSnapshot capture(Player player, ItemCodec codec, String worldId, String worldName, String owner, Location back, long now,
                                 List<ItemStack> extras) {
        PlayerInventory inventory = player.getInventory();
        List<VisitSnapshot.Effect> effects = new ArrayList<>();
        for (PotionEffect e : player.getActivePotionEffects())
            effects.add(new VisitSnapshot.Effect(e.getType().getKey().toString(), e.getDuration(), e.getAmplifier(), e.isAmbient(), e.hasParticles(), e.hasIcon()));
        return new VisitSnapshot(VisitSnapshot.FORMAT, player.getUniqueId(), worldId, worldName, owner, now,
                back.getWorld() == null ? "" : back.getWorld().getName(), back.getX(), back.getY(), back.getZ(), back.getYaw(), back.getPitch(),
                player.getGameMode().name(), codec.encode(inventory.getContents()), extras.isEmpty() ? "" : codec.encode(extras.toArray(ItemStack[]::new)),
                inventory.getHeldItemSlot(), effects, player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                player.getFireTicks(), player.getFreezeTicks(), player.getRemainingAir(), player.getFallDistance(), player.getAllowFlight(),
                player.isFlying(), player.getFlySpeed(), player.getWalkSpeed(), player.isCollidable(), player.isInvulnerable(), null);
    }

    /** Besuchszustand herstellen (der Marker ist zu diesem Zeitpunkt schon gesetzt). */
    static void prepare(Player player, VisitPolicy policy) {
        player.getInventory().clear();
        if (policy.isolateExperience()) { player.setExp(0); player.setLevel(0); player.setTotalExperience(0); }
        player.setItemOnCursor(null);
        player.setGameMode(policy.gameMode());
        if (policy.clearEffects()) for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setInvulnerable(policy.invulnerable());
        player.setCollidable(policy.collidable());
        if (policy.fullFood()) { player.setFoodLevel(20);player.setSaturation(20);player.setExhaustion(0); }
        player.setFireTicks(0);player.setFreezeTicks(0);player.setFallDistance(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFlying(false);player.setAllowFlight(false);
        player.setFlySpeed(0.1f);player.setWalkSpeed(0.2f);
    }

    static boolean hasTools(Player player, Predicate<ItemStack> tool) {
        for (ItemStack stack : player.getInventory().getContents()) if (!empty(stack) && tool.test(stack)) return true;
        return !empty(player.getItemOnCursor()) && tool.test(player.getItemOnCursor());
    }

    /** Entfernt Werkzeuge aus Inventar (inkl. Rüstung/Nebenhand) und Cursor; liefert die Anzahl. */
    static int stripTools(Player player, Predicate<ItemStack> tool) {
        int removed = 0;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++)
            if (!empty(contents[slot]) && tool.test(contents[slot])) { inventory.setItem(slot, null);removed++; }
        if (!empty(player.getItemOnCursor()) && tool.test(player.getItemOnCursor())) { player.setItemOnCursor(null);removed++; }
        return removed;
    }

    /**
     * Nimmt alles, was kein Werkzeug ist, aus Inventar, Cursor und Handwerksfeld (Review I1: während des Besuchs
     * erhalten). Nur rufen, wenn die Spielerdaten sicher im Besuchsstand sind.
     */
    static List<ItemStack> takeReceived(Player player, Predicate<ItemStack> tool) {
        List<ItemStack> received = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (empty(stack)) continue;
            if (!tool.test(stack)) received.add(stack);
            inventory.setItem(slot, null);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!empty(cursor) && !tool.test(cursor)) received.add(cursor);
        player.setItemOnCursor(null);
        CraftingInventory crafting = crafting(player);
        if (crafting != null) {
            for (ItemStack stack : crafting.getMatrix()) if (!empty(stack) && !tool.test(stack)) received.add(stack);
            crafting.setMatrix(new ItemStack[crafting.getMatrix().length]);
        }
        return received;
    }

    /** Schreibt den gesicherten Zustand zurück (Inventar komplett ersetzt). */
    static void apply(Player player, VisitSnapshot s, ItemStack[] saved) {
        try { player.setGameMode(GameMode.valueOf(s.gameMode())); } catch (IllegalArgumentException invalid) { player.setGameMode(GameMode.SURVIVAL); }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int i = 0; i < contents.length && i < saved.length; i++) contents[i] = empty(saved[i]) ? null : saved[i];
        inventory.setContents(contents);
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, s.heldSlot())));
        if (s.experienceLevel() >= 0) {
            player.setLevel(s.experienceLevel()); player.setTotalExperience(s.experienceTotal()); player.setExp(s.experienceProgress());
        }
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        for (VisitSnapshot.Effect e : s.effects()) {
            NamespacedKey key = NamespacedKey.fromString(e.type());
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type != null) player.addPotionEffect(new PotionEffect(type, e.duration(), e.amplifier(), e.ambient(), e.particles(), e.icon()));
        }
        player.setHealth(Math.max(0.5, Math.min(maxHealth(player), s.health())));
        player.setFoodLevel(s.food());player.setSaturation(s.saturation());player.setExhaustion(s.exhaustion());
        player.setFireTicks(s.fireTicks());player.setFallDistance(s.fallDistance());
        if (s.freezeTicks() >= 0) player.setFreezeTicks(s.freezeTicks());
        if (s.air() >= 0) player.setRemainingAir(Math.min(s.air(), player.getMaximumAir()));
        player.setFlySpeed(Math.max(-1f, Math.min(1f, s.flySpeed())));
        if (s.walkSpeed() >= 0) player.setWalkSpeed(Math.max(-1f, Math.min(1f, s.walkSpeed())));
        player.setAllowFlight(s.allowFlight());
        if (s.allowFlight()) player.setFlying(s.flying());
        player.setCollidable(s.collidable());
        player.setInvulnerable(s.invulnerable());
    }

    static double maxHealth(Player player) {
        try {
            double max = player.getMaxHealth();
            return max > 0 ? max : 20;
        } catch (RuntimeException unavailable) {
            return 20;
        }
    }

    static List<ItemStack> extras(VisitSnapshot s, ItemCodec codec) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : codec.decode(s.extraItems())) if (!empty(stack)) result.add(stack);
        return result;
    }

    /** Legt Items ins Inventar; liefert, was nicht passt. */
    static List<ItemStack> give(Player player, List<ItemStack> items) {
        if (items.isEmpty()) return List.of();
        Map<Integer, ItemStack> rest = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        return rest == null ? List.of() : List.copyOf(rest.values());
    }
}
