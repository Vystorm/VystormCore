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
 * Shared Bukkit gameplay equivalents for Paper convenience methods. @since 0.51.0
 * <p>Preferred access: {@code api.compat().gameplay()}.
 */
public final class GameplayCompat {
    private GameplayCompat() {}
    private static final NamespacedKey SPAWN_REASON = new NamespacedKey("vystorm_core", "spawn_reason");
    private static final NamespacedKey SUN_PROTECTED = new NamespacedKey("vystorm_core", "sun_protected");

    /** Registers persistent spawn provenance and Bukkit sunlight protection. Call once during Core enable. */
    public static void initialize(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void spawn(CreatureSpawnEvent event) {
                event.getEntity().getPersistentDataContainer()
                        .set(SPAWN_REASON, PersistentDataType.STRING, event.getSpawnReason().name());
            }

            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void combust(EntityCombustEvent event) {
                if (event.getClass() != EntityCombustEvent.class
                        || !event.getEntity().getPersistentDataContainer().has(SUN_PROTECTED, PersistentDataType.BYTE))
                    return;
                Location at = event.getEntity().getLocation();
                if (at.getWorld().getTime() < 12300 && !at.getWorld().hasStorm()
                        && at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) <= at.getBlockY())
                    event.setCancelled(true);
            }
        }, plugin);
    }

    public static CreatureSpawnEvent.SpawnReason getEntitySpawnReason(LivingEntity entity) {
        try {
            return entity.getEntitySpawnReason();
        } catch (NoSuchMethodError absent) {
            String reason = entity.getPersistentDataContainer().get(SPAWN_REASON, PersistentDataType.STRING);
            if (reason != null)
                try {
                    return CreatureSpawnEvent.SpawnReason.valueOf(reason);
                } catch (IllegalArgumentException ignored) {}
            return CreatureSpawnEvent.SpawnReason.DEFAULT;
        }
    }

    public static boolean fromMobSpawner(LivingEntity entity) {
        try {
            return entity.fromMobSpawner();
        } catch (NoSuchMethodError absent) {
            return getEntitySpawnReason(entity) == CreatureSpawnEvent.SpawnReason.SPAWNER;
        }
    }

    public static void setShouldBurnInDay(LivingEntity entity, boolean burn) {
        try {
            entity.getClass().getMethod("setShouldBurnInDay", boolean.class).invoke(entity, burn);
            return;
        } catch (NoSuchMethodException absent) { /* Bukkit uses the combustion listener. */
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to set sunlight behavior", failure);
        }
        if (burn) entity.getPersistentDataContainer().remove(SUN_PROTECTED);
        else entity.getPersistentDataContainer().set(SUN_PROTECTED, PersistentDataType.BYTE, (byte) 1);
    }

    public static boolean isEmpty(ItemStack stack) {
        if (stack == null) return true;
        try {
            return stack.isEmpty();
        } catch (NoSuchMethodError absent) {
            return stack.getAmount() <= 0 || stack.getType() == Material.AIR || stack.getType() == Material.CAVE_AIR
                    || stack.getType() == Material.VOID_AIR;
        }
    }

    public static boolean isSolid(org.bukkit.block.Block block) {
        try {
            return block.isSolid();
        } catch (NoSuchMethodError absent) {
            return block.getType().isSolid();
        }
    }

    public static boolean isSolid(Material material) {
        return material.isSolid();
    }

    public static boolean isReplaceable(org.bukkit.block.Block block) {
        try {
            return block.isReplaceable();
        } catch (NoSuchMethodError absent) {
            try {
                Object state = block.getBlockData().getClass().getMethod("getState").invoke(block.getBlockData());
                return (boolean) state.getClass().getMethod("canBeReplaced").invoke(state);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native block replacement query unavailable", failure);
            }
        }
    }

    public static boolean isDayTime(World world) {
        try {
            return world.isDayTime();
        } catch (NoSuchMethodError absent) {
            return world.getTime() < 12300;
        }
    }

    @SuppressWarnings("unchecked")
    public static GameRule<Boolean> booleanGameRule(String legacy, String modern) {
        for (String name : new String[] {legacy, modern})
            try {
                return (GameRule<Boolean>) GameRule.class.getField(name).get(null);
            } catch (ReflectiveOperationException absent) {}
        throw new IllegalArgumentException("Unknown game rule " + legacy + "/" + modern);
    }

    public static boolean hasFixedPose(Player player) {
        try {
            return player.hasFixedPose();
        } catch (NoSuchMethodError absent) {
            return false;
        }
    }

    public static ItemStack asOne(ItemStack stack) {
        return asQuantity(stack, 1);
    }

    public static ItemStack asQuantity(ItemStack stack, int quantity) {
        Objects.requireNonNull(stack, "stack");
        try {
            return stack.asQuantity(quantity);
        } catch (NoSuchMethodError absent) {
            ItemStack copy = stack.clone();
            copy.setAmount(quantity);
            return copy;
        }
    }

    public static Collection<LivingEntity> getNearbyLivingEntities(Location at, double radius) {
        return getNearbyLivingEntities(Objects.requireNonNull(at.getWorld()), at, radius);
    }

    public static Collection<LivingEntity> getNearbyLivingEntities(World world, Location at, double radius) {
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(at, radius, radius, radius))
            if (entity instanceof LivingEntity living) result.add(living);
        return result;
    }

    public static Entity getTargetEntity(Player player, int distance) {
        if (distance < 0) throw new IllegalArgumentException("distance must not be negative");
        try {
            return player.getTargetEntity(distance);
        } catch (NoSuchMethodError absent) {}
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(
                eye, eye.getDirection(), distance, FluidCollisionMode.NEVER, true, 0.0,
                entity -> entity != player && (!(entity instanceof Player p) || p.getGameMode() != GameMode.SPECTATOR));
        return hit == null ? null : hit.getHitEntity();
    }

    public static boolean isInLava(Entity entity) {
        try {
            return entity.isInLava();
        } catch (NoSuchMethodError absent) {
            var bounds = entity.getBoundingBox();
            World world = entity.getWorld();
            for (int x = (int) Math.floor(bounds.getMinX()); x < Math.ceil(bounds.getMaxX()); x++)
                for (int y = (int) Math.floor(bounds.getMinY()); y < Math.ceil(bounds.getMaxY()); y++)
                    for (int z = (int) Math.floor(bounds.getMinZ()); z < Math.ceil(bounds.getMaxZ()); z++)
                        if (world.isChunkLoaded(x >> 4, z >> 4) && world.getBlockAt(x, y, z).getType() == Material.LAVA)
                            return true;
            return false;
        }
    }

    public static boolean isUnderWater(Player player) {
        var block = player.getEyeLocation().getBlock();
        return block.getType() == Material.WATER
                || block.getBlockData() instanceof Waterlogged water && water.isWaterlogged();
    }

    public static boolean isInRain(Player player) {
        try {
            return player.isInRain();
        } catch (NoSuchMethodError absent) {
            Location at = player.getLocation();
            return at.getWorld().hasStorm() && at.getBlock().getTemperature() > 0.15 && at.getBlock().getHumidity() > 0
                    && at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) <= at.getBlockY();
        }
    }

    public static void heal(LivingEntity entity, double amount, EntityRegainHealthEvent.RegainReason reason) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid healing amount");
        if (entity.isDead() || amount == 0) return;
        EntityRegainHealthEvent event = new EntityRegainHealthEvent(entity, amount, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled() && event.getAmount() > 0 && Double.isFinite(event.getAmount()))
            entity.setHealth(Math.min(entity.getMaxHealth(), entity.getHealth() + event.getAmount()));
    }

    public static void setDropChance(EntityEquipment equipment, EquipmentSlot slot, float chance) {
        switch (slot.name()) {
            case "HAND" -> equipment.setItemInMainHandDropChance(chance);
            case "OFF_HAND" -> equipment.setItemInOffHandDropChance(chance);
            case "HEAD" -> equipment.setHelmetDropChance(chance);
            case "CHEST" -> equipment.setChestplateDropChance(chance);
            case "LEGS" -> equipment.setLeggingsDropChance(chance);
            case "FEET" -> equipment.setBootsDropChance(chance);
            default -> {
                try {
                    Class<?> nativeSlot = Class.forName("net.minecraft.world.entity.EquipmentSlot");
                    Object value = nativeSlot.getField(slot.name()).get(null);
                    Method method = equipment.getClass().getDeclaredMethod("setDropChance", nativeSlot, float.class);
                    method.setAccessible(true);
                    method.invoke(equipment, value, chance);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Unsupported equipment slot " + slot, failure);
                }
            }
        }
    }

    public static UUID getOwnerUniqueId(Tameable tameable) {
        return tameable.getOwner() == null ? null : tameable.getOwner().getUniqueId();
    }

    public static boolean canUseEquipmentSlot(LivingEntity entity, EquipmentSlot slot) {
        try {
            return entity.canUseEquipmentSlot(slot);
        } catch (NoSuchMethodError absent) {
            try {
                Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
                Class<?> nativeSlot = Class.forName("net.minecraft.world.entity.EquipmentSlot");
                String name = slot == EquipmentSlot.HAND
                        ? "MAINHAND"
                        : slot == EquipmentSlot.OFF_HAND ? "OFFHAND" : slot.name();
                return (boolean) handle.getClass().getMethod("canUseSlot", nativeSlot)
                        .invoke(handle, nativeSlot.getField(name).get(null));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Equipment slot adapter unavailable", failure);
            }
        }
    }

    public static void setLeftHanded(Mob entity, boolean value) {
        nativeBoolean(entity, "setLeftHanded", value);
    }

    public static void setAggressive(Mob entity, boolean value) {
        nativeBoolean(entity, "setAggressive", value);
    }

    private static void nativeBoolean(Entity entity, String method, boolean value) {
        try {
            try {
                entity.getClass().getMethod(method, boolean.class).invoke(entity, value);
                return;
            } catch (NoSuchMethodException absent) {}
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            handle.getClass().getMethod(method, boolean.class).invoke(handle, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native mob option unavailable: " + method, failure);
        }
    }

    public static void setIgnited(Creeper entity, boolean value) {
        try {
            entity.setIgnited(value);
        } catch (NoSuchMethodError absent) {
            try {
                Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
                Class<?> creeper = Class.forName("net.minecraft.world.entity.monster.Creeper");
                Field flag = creeper.getDeclaredField("DATA_IS_IGNITED");
                flag.setAccessible(true);
                Object data = handle.getClass().getMethod("getEntityData").invoke(handle);
                data.getClass().getMethod(
                        "set", Class.forName("net.minecraft.network.syncher.EntityDataAccessor"), Object.class)
                        .invoke(data, flag.get(null), value);
                if (!value) handle.getClass().getMethod("setSwellDir", int.class).invoke(handle, -1);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native creeper ignition adapter unavailable", failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void registerAttribute(LivingEntity entity, org.bukkit.attribute.Attribute attribute) {
        try {
            entity.registerAttribute(attribute);
        } catch (NoSuchMethodError absent) {
            if (entity.getAttribute(attribute) != null) return;
            try {
                Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
                Object map = handle.getClass().getMethod("getAttributes").invoke(handle);
                String craft = Bukkit.getServer().getClass().getPackageName();
                Object holder = Class.forName(craft + ".attribute.CraftAttribute")
                        .getMethod("bukkitToMinecraftHolder", org.bukkit.attribute.Attribute.class)
                        .invoke(null, attribute);
                Class<?> nativeInstance = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeInstance");
                Method changed = map.getClass().getDeclaredMethod("onAttributeModified", nativeInstance);
                changed.setAccessible(true);
                java.util.function.Consumer<Object> callback = instance -> {
                    try {
                        changed.invoke(map, instance);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                };
                Object instance = nativeInstance
                        .getConstructor(Class.forName("net.minecraft.core.Holder"), java.util.function.Consumer.class)
                        .newInstance(holder, callback);
                Field attributes = map.getClass().getDeclaredField("attributes");
                attributes.setAccessible(true);
                ((Map<Object, Object>) attributes.get(map)).putIfAbsent(holder, instance);
                callback.accept(instance);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native attribute registration unavailable", failure);
            }
        }
    }

    public static int getPossibleExperienceReward(Mob entity) {
        try {
            return entity.getPossibleExperienceReward();
        } catch (NoSuchMethodError absent) {
            try {
                Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
                Object world = entity.getWorld().getClass().getMethod("getHandle").invoke(entity.getWorld());
                Object killer = entity.getKiller() == null
                        ? null
                        : entity.getKiller().getClass().getMethod("getHandle").invoke(entity.getKiller());
                return (int) handle.getClass().getMethod(
                        "getExperienceReward", Class.forName("net.minecraft.server.level.ServerLevel"),
                        Class.forName("net.minecraft.world.entity.Entity")).invoke(handle, world, killer);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native experience calculation unavailable", failure);
            }
        }
    }

    public static boolean isCritical(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        try {
            return event.isCritical();
        } catch (NoSuchMethodError absent) {
            if (event.getDamager() instanceof AbstractArrow arrow) return arrow.isCritical();
            if (!(event.getDamager() instanceof Player player)) return false;
            return player.getFallDistance() > 0 && !player.isOnGround() && !player.isSprinting() && !player.isInWater()
                    && !player.isInsideVehicle()
                    && !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS)
                    && !Tag.CLIMBABLE.isTagged(player.getLocation().getBlock().getType());
        }
    }

    public static Double defaultAttributeValue(EntityType type, org.bukkit.attribute.Attribute attribute) {
        try {
            if (!type.hasDefaultAttributes()) return null;
            AttributeInstance value = type.getDefaultAttributes().getAttribute(attribute);
            return value == null ? null : value.getBaseValue();
        } catch (NoSuchMethodError absent) {
            try {
                String craft = Bukkit.getServer().getClass().getPackageName();
                Object nativeType = Class.forName(craft + ".entity.CraftEntityType")
                        .getMethod("bukkitToMinecraft", EntityType.class).invoke(null, type);
                Object supplier = Class.forName("net.minecraft.world.entity.ai.attributes.DefaultAttributes")
                        .getMethod("getSupplier", Class.forName("net.minecraft.world.entity.EntityType"))
                        .invoke(null, nativeType);
                if (supplier == null) return null;
                Object holder = Class.forName(craft + ".attribute.CraftAttribute")
                        .getMethod("bukkitToMinecraftHolder", org.bukkit.attribute.Attribute.class)
                        .invoke(null, attribute);
                Class<?> holderClass = Class.forName("net.minecraft.core.Holder");
                if (!(boolean) supplier.getClass().getMethod("hasAttribute", holderClass).invoke(supplier, holder))
                    return null;
                return (double) supplier.getClass().getMethod("getBaseValue", holderClass).invoke(supplier, holder);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native default attributes unavailable", failure);
            }
        }
    }

    public static Entity releaseShoulderEntity(Player player, boolean left) {
        try {
            return left ? player.releaseLeftShoulderEntity() : player.releaseRightShoulderEntity();
        } catch (NoSuchMethodError absent) {
            Entity stored = left ? player.getShoulderEntityLeft() : player.getShoulderEntityRight();
            if (stored == null) return null;
            // Clear the shoulder before the spawn event so observers cannot find two copies.
            if (left) player.setShoulderEntityLeft(null);
            else player.setShoulderEntityRight(null);
            try {
                if (StorageCompat.spawnAt(stored, player.getLocation().add(0, .7, 0))) return stored;
            } catch (RuntimeException failure) {
                if (left) player.setShoulderEntityLeft(stored);
                else player.setShoulderEntityRight(stored);
                throw failure;
            }
            if (left) player.setShoulderEntityLeft(stored);
            else player.setShoulderEntityRight(stored);
            return null;
        }
    }

    public static AttributeModifier getModifier(AttributeInstance instance, UUID id) {
        try {
            return instance.getModifier(id);
        } catch (NoSuchMethodError absent) {}
        for (AttributeModifier modifier : instance.getModifiers())
            if (modifier.getUniqueId().equals(id)) return modifier;
        return null;
    }

    public static void removeModifier(AttributeInstance instance, UUID id) {
        try {
            instance.removeModifier(id);
            return;
        } catch (NoSuchMethodError absent) {}
        AttributeModifier modifier = getModifier(instance, id);
        if (modifier != null) instance.removeModifier(modifier);
    }

    public static void removeModifier(AttributeInstance instance, AttributeModifier modifier) {
        instance.removeModifier(modifier);
    }

    public static AttributeModifier getModifier(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            try {
                if (modifier.getClass().getMethod("getKey").invoke(modifier).equals(key)) return modifier;
            } catch (NoSuchMethodException absent) {
                if (modifier.getName().equals(key.toString())) return modifier;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Attribute modifier key unavailable", failure);
            }
        }
        return null;
    }

    public static void removeModifier(AttributeInstance instance, NamespacedKey key) {
        AttributeModifier modifier = getModifier(instance, key);
        if (modifier != null) instance.removeModifier(modifier);
    }

    public static void addTransientModifier(AttributeInstance instance, AttributeModifier modifier) {
        try {
            instance.addTransientModifier(modifier);
        } catch (NoSuchMethodError absent) {
            try {
                Field field = instance.getClass().getDeclaredField("handle");
                field.setAccessible(true);
                Object handle = field.get(instance);
                Object nativeModifier =
                        instance.getClass().getMethod("convert", AttributeModifier.class).invoke(null, modifier);
                handle.getClass().getMethod("addTransientModifier", nativeModifier.getClass())
                        .invoke(handle, nativeModifier);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Transient attribute adapter unavailable", failure);
            }
        }
    }

    public static void damageItemStack(Player player, EquipmentSlot slot, int amount) {
        try {
            player.damageItemStack(slot, amount);
            return;
        } catch (NoSuchMethodError absent) { /* Bukkit durability path below. */
        }
        damageBukkit(player, slot, amount);
    }

    static void damageBukkit(Player player, EquipmentSlot slot, int amount) {
        ItemStack stack = player.getInventory().getItem(slot);
        damage(stack, amount, player, slot);
        player.getInventory().setItem(slot, stack);
    }

    public static void damage(ItemStack stack, int amount, LivingEntity entity) {
        try {
            stack.damage(amount, entity);
            return;
        } catch (NoSuchMethodError absent) {}
        EquipmentSlot slot = EquipmentSlot.HAND;
        if (entity.getEquipment() != null)
            for (EquipmentSlot candidate : EquipmentSlot.values()) {
                try {
                    if (entity.getEquipment().getItem(candidate) == stack) {
                        slot = candidate;
                        break;
                    }
                } catch (IllegalArgumentException ignored) { /* This entity has no such slot. */
                }
            }
        damage(stack, amount, entity, slot);
    }

    public static ItemStack damageResult(ItemStack stack, int amount, LivingEntity entity) {
        try {
            return stack.damage(amount, entity);
        } catch (NoSuchMethodError absent) {
            damage(stack, amount, entity);
            return stack;
        }
    }

    private static void damage(ItemStack stack, int amount, LivingEntity entity, EquipmentSlot slot) {
        if (amount <= 0 || entity instanceof Player player && player.getGameMode() == GameMode.CREATIVE) return;
        if (isEmpty(stack) || !(stack.getItemMeta() instanceof Damageable meta) || meta.isUnbreakable()) return;
        int maximum = stack.getType().getMaxDurability();
        try {
            if (meta.hasMaxDamage()) maximum = meta.getMaxDamage();
        } catch (NoSuchMethodError ignored) {}
        if (maximum <= 0) return;
        Enchantment unbreaking = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        int level = unbreaking == null ? 0 : stack.getEnchantmentLevel(unbreaking), damage = 0;
        boolean armor = Set.of("HEAD", "CHEST", "LEGS", "FEET").contains(slot.name());
        for (int i = 0; i < amount; i++)
            if (level <= 0 || armor && ThreadLocalRandom.current().nextFloat() < 0.6f
                    || ThreadLocalRandom.current().nextInt(level + 1) == 0)
                damage++;
        if (damage == 0) return;
        if (entity instanceof Player player) {
            PlayerItemDamageEvent event = new PlayerItemDamageEvent(player, stack, damage);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || event.getDamage() <= 0) return;
            damage = event.getDamage();
        }
        if ((long) meta.getDamage() + damage >= maximum) {
            if (entity instanceof Player player)
                Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(player, stack.clone()));
            stack.setAmount(stack.getAmount() - 1);
            meta.setDamage(0);
        } else meta.setDamage(meta.getDamage() + damage);
        stack.setItemMeta(meta);
    }
}
