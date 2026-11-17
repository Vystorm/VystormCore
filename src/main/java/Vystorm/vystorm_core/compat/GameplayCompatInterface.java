package Vystorm.vystorm_core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.RayTraceResult;

/**
 * Manager view of {@link GameplayCompat}, reached through {@code api.compat().gameplay()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link GameplayCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface GameplayCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().gameplay()}. */
    GameplayCompatInterface DEFAULT = new GameplayCompatInterface() {};

    /** See {@link GameplayCompat#getEntitySpawnReason}. */
    default CreatureSpawnEvent.SpawnReason getEntitySpawnReason(LivingEntity entity) { return GameplayCompat.getEntitySpawnReason(entity); }

    /** See {@link GameplayCompat#fromMobSpawner}. */
    default boolean fromMobSpawner(LivingEntity entity) { return GameplayCompat.fromMobSpawner(entity); }

    /** See {@link GameplayCompat#setShouldBurnInDay}. */
    default void setShouldBurnInDay(LivingEntity entity, boolean burn) { GameplayCompat.setShouldBurnInDay(entity, burn); }

    /** See {@link GameplayCompat#isEmpty}. */
    default boolean isEmpty(ItemStack stack) { return GameplayCompat.isEmpty(stack); }

    /** See {@link GameplayCompat#isSolid}. */
    default boolean isSolid(org.bukkit.block.Block block) { return GameplayCompat.isSolid(block); }

    /** See {@link GameplayCompat#isSolid}. */
    default boolean isSolid(Material material) { return GameplayCompat.isSolid(material); }

    /** See {@link GameplayCompat#isReplaceable}. */
    default boolean isReplaceable(org.bukkit.block.Block block) { return GameplayCompat.isReplaceable(block); }

    /** See {@link GameplayCompat#isDayTime}. */
    default boolean isDayTime(World world) { return GameplayCompat.isDayTime(world); }

    /** See {@link GameplayCompat#booleanGameRule}. */
    default GameRule<Boolean> booleanGameRule(String legacy, String modern) { return GameplayCompat.booleanGameRule(legacy, modern); }

    /** See {@link GameplayCompat#hasFixedPose}. */
    default boolean hasFixedPose(Player player) { return GameplayCompat.hasFixedPose(player); }

    /** See {@link GameplayCompat#asOne}. */
    default ItemStack asOne(ItemStack stack) { return GameplayCompat.asOne(stack); }

    /** See {@link GameplayCompat#asQuantity}. */
    default ItemStack asQuantity(ItemStack stack, int quantity) { return GameplayCompat.asQuantity(stack, quantity); }

    /** See {@link GameplayCompat#getNearbyLivingEntities}. */
    default Collection<LivingEntity> getNearbyLivingEntities(Location at, double radius) { return GameplayCompat.getNearbyLivingEntities(at, radius); }

    /** See {@link GameplayCompat#getNearbyLivingEntities}. */
    default Collection<LivingEntity> getNearbyLivingEntities(World world, Location at, double radius) {
        return GameplayCompat.getNearbyLivingEntities(world, at, radius);
    }

    /** See {@link GameplayCompat#getTargetEntity}. */
    default Entity getTargetEntity(Player player, int distance) { return GameplayCompat.getTargetEntity(player, distance); }

    /** See {@link GameplayCompat#isInLava}. */
    default boolean isInLava(Entity entity) { return GameplayCompat.isInLava(entity); }

    /** See {@link GameplayCompat#isUnderWater}. */
    default boolean isUnderWater(Player player) { return GameplayCompat.isUnderWater(player); }

    /** See {@link GameplayCompat#isInRain}. */
    default boolean isInRain(Player player) { return GameplayCompat.isInRain(player); }

    /** See {@link GameplayCompat#heal}. */
    default void heal(LivingEntity entity, double amount, EntityRegainHealthEvent.RegainReason reason) { GameplayCompat.heal(entity, amount, reason); }

    /** See {@link GameplayCompat#setDropChance}. */
    default void setDropChance(EntityEquipment equipment, EquipmentSlot slot, float chance) { GameplayCompat.setDropChance(equipment, slot, chance); }

    /** See {@link GameplayCompat#getOwnerUniqueId}. */
    default UUID getOwnerUniqueId(Tameable tameable) { return GameplayCompat.getOwnerUniqueId(tameable); }

    /** See {@link GameplayCompat#canUseEquipmentSlot}. */
    default boolean canUseEquipmentSlot(LivingEntity entity, EquipmentSlot slot) { return GameplayCompat.canUseEquipmentSlot(entity, slot); }

    /** See {@link GameplayCompat#setLeftHanded}. */
    default void setLeftHanded(Mob entity, boolean value) { GameplayCompat.setLeftHanded(entity, value); }

    /** See {@link GameplayCompat#setAggressive}. */
    default void setAggressive(Mob entity, boolean value) { GameplayCompat.setAggressive(entity, value); }

    /** See {@link GameplayCompat#setIgnited}. */
    default void setIgnited(Creeper entity, boolean value) { GameplayCompat.setIgnited(entity, value); }

    /** See {@link GameplayCompat#registerAttribute}. */
    default void registerAttribute(LivingEntity entity, org.bukkit.attribute.Attribute attribute) {
        GameplayCompat.registerAttribute(entity, attribute);
    }

    /** See {@link GameplayCompat#getPossibleExperienceReward}. */
    default int getPossibleExperienceReward(Mob entity) { return GameplayCompat.getPossibleExperienceReward(entity); }

    /** See {@link GameplayCompat#isCritical}. */
    default boolean isCritical(org.bukkit.event.entity.EntityDamageByEntityEvent event) { return GameplayCompat.isCritical(event); }

    /** See {@link GameplayCompat#defaultAttributeValue}. */
    default Double defaultAttributeValue(EntityType type, org.bukkit.attribute.Attribute attribute) {
        return GameplayCompat.defaultAttributeValue(type, attribute);
    }

    /** See {@link GameplayCompat#releaseShoulderEntity}. */
    default Entity releaseShoulderEntity(Player player, boolean left) { return GameplayCompat.releaseShoulderEntity(player, left); }

    /** See {@link GameplayCompat#getModifier}. */
    default AttributeModifier getModifier(AttributeInstance instance, UUID id) { return GameplayCompat.getModifier(instance, id); }

    /** See {@link GameplayCompat#removeModifier}. */
    default void removeModifier(AttributeInstance instance, UUID id) { GameplayCompat.removeModifier(instance, id); }

    /** See {@link GameplayCompat#removeModifier}. */
    default void removeModifier(AttributeInstance instance, AttributeModifier modifier) { GameplayCompat.removeModifier(instance, modifier); }

    /** See {@link GameplayCompat#getModifier}. */
    default AttributeModifier getModifier(AttributeInstance instance, NamespacedKey key) { return GameplayCompat.getModifier(instance, key); }

    /** See {@link GameplayCompat#removeModifier}. */
    default void removeModifier(AttributeInstance instance, NamespacedKey key) { GameplayCompat.removeModifier(instance, key); }

    /** See {@link GameplayCompat#addTransientModifier}. */
    default void addTransientModifier(AttributeInstance instance, AttributeModifier modifier) {
        GameplayCompat.addTransientModifier(instance, modifier);
    }

    /** See {@link GameplayCompat#damageItemStack}. */
    default void damageItemStack(Player player, EquipmentSlot slot, int amount) { GameplayCompat.damageItemStack(player, slot, amount); }

    /** See {@link GameplayCompat#damage}. */
    default void damage(ItemStack stack, int amount, LivingEntity entity) { GameplayCompat.damage(stack, amount, entity); }

    /** See {@link GameplayCompat#damageResult}. */
    default ItemStack damageResult(ItemStack stack, int amount, LivingEntity entity) { return GameplayCompat.damageResult(stack, amount, entity); }
}
