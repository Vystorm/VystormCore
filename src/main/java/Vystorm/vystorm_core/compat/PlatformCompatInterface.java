package Vystorm.vystorm_core.compat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Manager view of {@link PlatformCompat}, reached through {@code api.compat().platform()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link PlatformCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface PlatformCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().platform()}. */
    PlatformCompatInterface DEFAULT = new PlatformCompatInterface() {};

    /** See {@link PlatformCompat#legacy}. */
    default String legacy(Component value) { return PlatformCompat.legacy(value); }

    /** See {@link PlatformCompat#sendMessage}. */
    default void sendMessage(CommandSender recipient, Component message) { PlatformCompat.sendMessage(recipient, message); }

    /** String overload permits mechanically migrated common call sites without losing legacy formatting. */
    default void sendMessage(CommandSender recipient, String message) { PlatformCompat.sendMessage(recipient, message); }

    /** See {@link PlatformCompat#sendMessage}. */
    default void sendMessage(CommandSender recipient, String... messages) { PlatformCompat.sendMessage(recipient, messages); }

    /** See {@link PlatformCompat#sendActionBar}. */
    default void sendActionBar(Player player, String message) { PlatformCompat.sendActionBar(player, message); }

    /** See {@link PlatformCompat#sendActionBar}. */
    default void sendActionBar(Player player, Component message) { PlatformCompat.sendActionBar(player, message); }

    /** See {@link PlatformCompat#playSound}. */
    default void playSound(Player player, net.kyori.adventure.sound.Sound sound) { PlatformCompat.playSound(player, sound); }

    /** See {@link PlatformCompat#text}. */
    default void text(TextDisplay display, Component text) { PlatformCompat.text(display, text); }

    /** See {@link PlatformCompat#sendMultiBlockChange}. */
    default void sendMultiBlockChange(Player player, Map<Location, org.bukkit.block.data.BlockData> blocks) {
        PlatformCompat.sendMultiBlockChange(player, blocks);
    }

    /** See {@link PlatformCompat#showTitle}. */
    default void showTitle(Player player, Title title) { PlatformCompat.showTitle(player, title); }

    /** See {@link PlatformCompat#showBossBar}. */
    default void showBossBar(Player player, BossBar bar) { PlatformCompat.showBossBar(player, bar); }

    /** See {@link PlatformCompat#hideBossBar}. */
    default void hideBossBar(Player player, BossBar bar) { PlatformCompat.hideBossBar(player, bar); }

    /** See {@link PlatformCompat#displayName}. */
    default void displayName(ItemMeta meta, Component name) { PlatformCompat.displayName(meta, name); }

    /** See {@link PlatformCompat#displayName}. */
    default Component displayName(ItemMeta meta) { return PlatformCompat.displayName(meta); }

    /** See {@link PlatformCompat#displayName}. */
    default Component displayName(ItemStack item) { return PlatformCompat.displayName(item); }

    /** See {@link PlatformCompat#lore}. */
    default void lore(ItemMeta meta, List<? extends Component> lines) { PlatformCompat.lore(meta, lines); }

    /** See {@link PlatformCompat#lore}. */
    default List<Component> lore(ItemMeta meta) { return PlatformCompat.lore(meta); }

    /** See {@link PlatformCompat#lore}. */
    default void lore(ItemStack item, List<? extends Component> lines) { PlatformCompat.lore(item, lines); }

    /** See {@link PlatformCompat#lore}. */
    default List<Component> lore(ItemStack item) { return PlatformCompat.lore(item); }

    /** See {@link PlatformCompat#displayName}. */
    default void displayName(Player player, Component name) { PlatformCompat.displayName(player, name); }

    /** See {@link PlatformCompat#displayName}. */
    default Component displayName(Player player) { return PlatformCompat.displayName(player); }

    /** See {@link PlatformCompat#editMeta}. */
    default boolean editMeta(ItemStack item, Consumer<ItemMeta> edit) { return PlatformCompat.editMeta(item, edit); }

    /** See {@link PlatformCompat#editMeta}. */
    default <M extends ItemMeta> boolean editMeta(ItemStack item, Class<M> type, Consumer<? super M> edit) { return PlatformCompat.editMeta(item, type, edit); }

    /** See {@link PlatformCompat#createInventory}. */
    default Inventory createInventory(InventoryHolder holder, int size, Component title) { return PlatformCompat.createInventory(holder, size, title); }

    /** See {@link PlatformCompat#createInventory}. */
    default Inventory createInventory(InventoryHolder holder, int size, String title) { return PlatformCompat.createInventory(holder, size, title); }

    /** See {@link PlatformCompat#createInventory}. */
    default Inventory createInventory(InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, Component title) {
        return PlatformCompat.createInventory(holder, type, title);
    }

    /** See {@link PlatformCompat#createInventory}. */
    default Inventory createInventory(InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, String title) {
        return PlatformCompat.createInventory(holder, type, title);
    }

    /** See {@link PlatformCompat#customName}. */
    default void customName(Nameable target, Component name) { PlatformCompat.customName(target, name); }

    /** See {@link PlatformCompat#registerNewObjective}. */
    default org.bukkit.scoreboard.Objective registerNewObjective(org.bukkit.scoreboard.Scoreboard board, String name, org.bukkit.scoreboard.Criteria criteria, Component title) {
        return PlatformCompat.registerNewObjective(board, name, criteria, title);
    }

    /** See {@link PlatformCompat#customName}. */
    default void customName(org.bukkit.scoreboard.Score score, Component name) { PlatformCompat.customName(score, name); }

    /** See {@link PlatformCompat#blankNumberFormat}. */
    default void blankNumberFormat(org.bukkit.scoreboard.Objective objective) { PlatformCompat.blankNumberFormat(objective); }

    /** See {@link PlatformCompat#customName}. */
    default Component customName(Nameable target) { return PlatformCompat.customName(target); }

    /** See {@link PlatformCompat#locale}. */
    default Locale locale(Player player) { return PlatformCompat.locale(player); }

    /** See {@link PlatformCompat#translationKey}. */
    default String translationKey(Material type) { return PlatformCompat.translationKey(type); }

    /** See {@link PlatformCompat#translationKey}. */
    default String translationKey(EntityType type) { return PlatformCompat.translationKey(type); }

    /** See {@link PlatformCompat#isEmpty}. */
    default boolean isEmpty(ItemStack item) { return PlatformCompat.isEmpty(item); }

    /** See {@link PlatformCompat#emptyItem}. */
    default ItemStack emptyItem() { return PlatformCompat.emptyItem(); }

    /** See {@link PlatformCompat#setResourcePack}. */
    default void setResourcePack(Player player, UUID id, String url, byte[] hash, Component prompt, boolean required) {
        PlatformCompat.setResourcePack(player, id, url, hash, prompt, required);
    }

    /** See {@link PlatformCompat#getState}. */
    default BlockState getState(Block block, boolean snapshot) { return PlatformCompat.getState(block, snapshot); }

    /** See {@link PlatformCompat#getChunkKey}. */
    default long getChunkKey(Chunk chunk) { return PlatformCompat.getChunkKey(chunk); }

    /** See {@link PlatformCompat#getChunkKey}. */
    default long getChunkKey(int x, int z) { return PlatformCompat.getChunkKey(x, z); }

    /** See {@link PlatformCompat#getBlockKey}. */
    default long getBlockKey(Block block) { return PlatformCompat.getBlockKey(block); }

    /** See {@link PlatformCompat#getEntity}. */
    default Entity getEntity(World world, UUID id) { return PlatformCompat.getEntity(world, id); }

    /** See {@link PlatformCompat#getOwnerUniqueId}. */
    default UUID getOwnerUniqueId(Tameable entity) { return PlatformCompat.getOwnerUniqueId(entity); }

    /** See {@link PlatformCompat#getTargetBlockFace}. */
    default org.bukkit.block.BlockFace getTargetBlockFace(Player player, int distance) { return PlatformCompat.getTargetBlockFace(player, distance); }

    /** See {@link PlatformCompat#isChunkLoaded}. */
    default boolean isChunkLoaded(Location location) { return PlatformCompat.isChunkLoaded(location); }

    /** See {@link PlatformCompat#getCurrentTick}. */
    default int getCurrentTick() { return PlatformCompat.getCurrentTick(); }

    /** See {@link PlatformCompat#getSendViewDistance}. */
    default int getSendViewDistance(Player player) { return PlatformCompat.getSendViewDistance(player); }

    /** See {@link PlatformCompat#getMinecraftVersion}. */
    default String getMinecraftVersion() { return PlatformCompat.getMinecraftVersion(); }

    /** Returns the online profile, including its existing skin properties, across both API return types. */
    default org.bukkit.profile.PlayerProfile getPlayerProfile(Player player) { return PlatformCompat.getPlayerProfile(player); }

    /** See {@link PlatformCompat#isStopping}. */
    default boolean isStopping() { return PlatformCompat.isStopping(); }

    /** See {@link PlatformCompat#getTPS}. */
    default double[] getTPS() { return PlatformCompat.getTPS(); }

    /** See {@link PlatformCompat#getAverageTickTime}. */
    default double getAverageTickTime() { return PlatformCompat.getAverageTickTime(); }

    /** Cache-only lookup: never calls name-based Bukkit.getOfflinePlayer (which may make a network request). */
    default OfflinePlayer getOfflinePlayerIfCached(String name) { return PlatformCompat.getOfflinePlayerIfCached(name); }

    /** See {@link PlatformCompat#getNearbyPlayers}. */
    default Collection<Player> getNearbyPlayers(Location location, double radius) { return PlatformCompat.getNearbyPlayers(location, radius); }

    /** See {@link PlatformCompat#getNearbyEntitiesByType}. */
    default <T extends Entity> Collection<T> getNearbyEntitiesByType(World world, Class<T> type, Location location, double radius) {
        return PlatformCompat.getNearbyEntitiesByType(world, type, location, radius);
    }

    /** See {@link PlatformCompat#getNearbyEntitiesByType}. */
    default <T extends Entity> Collection<T> getNearbyEntitiesByType(World world, Class<T> type, Location location, double x, double y, double z) {
        return PlatformCompat.getNearbyEntitiesByType(world, type, location, x, y, z);
    }

    /** See {@link PlatformCompat#getCommandMap}. */
    default CommandMap getCommandMap() { return PlatformCompat.getCommandMap(); }

    /** See {@link PlatformCompat#getKnownCommands}. */
    default Map<String, Command> getKnownCommands(CommandMap map) { return PlatformCompat.getKnownCommands(map); }

    /** See {@link PlatformCompat#getChunkAtAsync}. */
    default CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) { return PlatformCompat.getChunkAtAsync(world, x, z); }

    /** See {@link PlatformCompat#getChunkAtAsync}. */
    default CompletableFuture<Chunk> getChunkAtAsync(World world, Location location) { return PlatformCompat.getChunkAtAsync(world, location); }

    /** See {@link PlatformCompat#getChunkAtAsync}. */
    default CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z, boolean generate) {
        return PlatformCompat.getChunkAtAsync(world, x, z, generate);
    }

    /** See {@link PlatformCompat#teleportAsync}. */
    default CompletableFuture<Boolean> teleportAsync(Entity entity, Location target) { return PlatformCompat.teleportAsync(entity, target); }

    /** See {@link PlatformCompat#teleportAsync}. */
    default CompletableFuture<Boolean> teleportAsync(Entity entity, Location target, TeleportCause cause) {
        return PlatformCompat.teleportAsync(entity, target, cause);
    }

    /** See {@link PlatformCompat#lockFreezeTicks}. */
    default void lockFreezeTicks(Player player, boolean locked) { PlatformCompat.lockFreezeTicks(player, locked); }

    /** Set plugin-controlled freeze state; required while the Bukkit freeze lock is active. */
    default void setFreezeTicks(Player player, int ticks) { PlatformCompat.setFreezeTicks(player, ticks); }

    /** See {@link PlatformCompat#enchantWithLevels}. */
    default ItemStack enchantWithLevels(ItemStack item, int level, boolean treasure, Random random) {
        return PlatformCompat.enchantWithLevels(item, level, treasure, random);
    }

    /** See {@link PlatformCompat#calculateTotalExperiencePoints}. */
    default int calculateTotalExperiencePoints(Player player) { return PlatformCompat.calculateTotalExperiencePoints(player); }

    /** See {@link PlatformCompat#setExperienceLevelAndProgress}. */
    default void setExperienceLevelAndProgress(Player player, int total) { PlatformCompat.setExperienceLevelAndProgress(player, total); }
}
