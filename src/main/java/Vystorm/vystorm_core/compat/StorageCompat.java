package Vystorm.vystorm_core.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Lossless native item/entity persistence shared by Paper and CraftBukkit/Spigot 26.x.
 * Uses Paper's own API when present; the CraftBukkit adapter writes the same compressed,
 * versioned NBT format, including all data components and persistent plugin data.
 * Invalid or newer data fails before it can replace a stored item/entity.
 * All methods require the server thread. No entity is added by {@link #deserializeEntity}.
 * <p>Preferred access: {@code api.compat().storage()}.
 *
 * @since 0.51.0
 */
public final class StorageCompat {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private StorageCompat() {}

    public static byte[] serializeAsBytes(ItemStack item) {
        Objects.requireNonNull(item, "item");
        Method nativeMethod = optional(ItemStack.class, "serializeAsBytes");
        if (nativeMethod != null) return (byte[]) call(nativeMethod, item);
        if (item.getAmount() <= 0 || item.getType().isAir()) throw new IllegalArgumentException("Empty item cannot be serialized");
        try {
            Object handle = call(craft("inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class), null, item);
            Object codec = nms("world.item.ItemStack").getField("CODEC").get(null);
            Object encoded = call(type("com.mojang.serialization.Encoder").getMethod("encodeStart", dynamicOps(), Object.class), codec, registryOps(), handle);
            return writeTag(result(encoded));
        } catch (ReflectiveOperationException failure) { throw unavailable("serialize item", failure); }
    }

    public static ItemStack deserializeBytes(byte[] data) {
        validateBytes(data);
        Method nativeMethod = optional(ItemStack.class, "deserializeBytes", byte[].class);
        if (nativeMethod != null) return (ItemStack) call(nativeMethod, null, (Object) data);
        try {
            Object tag = upgrade(readTag(data), "ITEM_STACK");
            Object codec = nms("world.item.ItemStack").getField("CODEC").get(null);
            Object decoded = call(type("com.mojang.serialization.Decoder").getMethod("parse", dynamicOps(), Object.class), codec, registryOps(), tag);
            Object handle = result(decoded);
            return (ItemStack) call(craft("inventory.CraftItemStack").getMethod("asBukkitCopy", nms("world.item.ItemStack")), null, handle);
        } catch (ReflectiveOperationException failure) { throw unavailable("deserialize item", failure); }
    }

    /** Serializes a single animal/entity, excluding passengers just like Paper's default. */
    public static byte[] serializeEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player) throw new IllegalArgumentException("Player persistence belongs to the server");
        Method nativeMethod = optional(org.bukkit.UnsafeValues.class, "serializeEntity", Entity.class);
        if (nativeMethod != null) return (byte[]) call(nativeMethod, Bukkit.getUnsafe(), entity);
        EntitySnapshot snapshot = entity.createSnapshot();
        if (snapshot == null) throw new IllegalArgumentException("Entity cannot be persisted");
        try {
            Object original = call(snapshot.getClass().getMethod("getData"), snapshot);
            Object tag = call(nms("nbt.CompoundTag").getMethod("copy"), original);
            call(nms("nbt.CompoundTag").getMethod("remove", String.class), tag, "Passengers");
            return writeTag(tag);
        } catch (ReflectiveOperationException failure) { throw unavailable("serialize entity", failure); }
    }

    /** Returns a detached entity with a fresh UUID. Its original PDC identity is preserved. */
    public static Entity deserializeEntity(byte[] data, World world) {
        validateBytes(data); Objects.requireNonNull(world, "world");
        Method nativeMethod = optional(org.bukkit.UnsafeValues.class, "deserializeEntity", byte[].class, World.class);
        if (nativeMethod != null) return (Entity) call(nativeMethod, Bukkit.getUnsafe(), data, world);
        try {
            Object tag = upgrade(readTag(data), "ENTITY");
            Class<?> compound = nms("nbt.CompoundTag");
            for (String key : new String[]{"Passengers", "UUID", "UUIDMost", "UUIDLeast"}) call(compound.getMethod("remove", String.class), tag, key);
            Object snapshot = call(craft("entity.CraftEntitySnapshot").getMethod("create", compound), null, tag);
            if (!(snapshot instanceof EntitySnapshot valid)) throw new IllegalArgumentException("Stored entity type is invalid");
            Entity entity = valid.createEntity(world);
            if (entity == null || entity instanceof Player || entity.isInWorld()) throw new IllegalArgumentException("Stored entity could not be restored detached");
            return entity;
        } catch (ReflectiveOperationException failure) { throw unavailable("deserialize entity", failure); }
    }

    /** Adds a detached entity once, respecting normal Bukkit spawn cancellation. */
    public static boolean spawnAt(Entity entity, Location location) {
        Objects.requireNonNull(entity, "entity"); Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location world");
        if (entity.isInWorld()) throw new IllegalArgumentException("Entity is already in a world");
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        Method nativeMethod = optional(Entity.class, "spawnAt", Location.class);
        if (nativeMethod != null) return (boolean) call(nativeMethod, entity, location);
        if (!entity.getWorld().equals(location.getWorld())) throw new IllegalArgumentException("Detached entity belongs to another world");
        if (!entity.teleport(location)) return false;
        location.getWorld().addEntity(entity);
        // CraftBukkit can add an entity to a newly loaded chunk before that chunk becomes
        // entity-ticking. isValid() stays false during that transition; it is not a spawn result.
        return entity.isInWorld() && !entity.isDead();
    }

    static void validateBytes(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0 || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid serialized data length");
    }

    private static Object readTag(byte[] data) throws ReflectiveOperationException {
        Object budget = call(nms("nbt.NbtAccounter").getMethod("create", long.class), null, (long) MAX_BYTES);
        return call(nms("nbt.NbtIo").getMethod("readCompressed", InputStream.class, nms("nbt.NbtAccounter")), null, new ByteArrayInputStream(data), budget);
    }

    private static byte[] writeTag(Object tag) throws ReflectiveOperationException {
        call(nms("nbt.NbtUtils").getMethod("addCurrentDataVersion", nms("nbt.CompoundTag")), null, tag);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        call(nms("nbt.NbtIo").getMethod("writeCompressed", nms("nbt.CompoundTag"), OutputStream.class), null, tag, output);
        byte[] bytes = output.toByteArray(); validateBytes(bytes); return bytes;
    }

    private static Object upgrade(Object tag, String reference) throws ReflectiveOperationException {
        int from = (int) call(nms("nbt.NbtUtils").getMethod("getDataVersion", nms("nbt.CompoundTag"), int.class), null, tag, 0);
        int current = Bukkit.getUnsafe().getDataVersion();
        if (from > current) throw new IllegalArgumentException("Stored data is newer than this server; downgrades are not supported");
        if (from == current) return tag;
        Object fixer = call(nms("util.datafix.DataFixers").getMethod("getDataFixer"), null);
        Object kind = nms("util.datafix.fixes.References").getField(reference).get(null);
        Class<?> dynamic = type("com.mojang.serialization.Dynamic");
        Object value = dynamic.getConstructor(dynamicOps(), Object.class).newInstance(nbtOps(), tag);
        Object updated = call(type("com.mojang.datafixers.DataFixer").getMethod("update", type("com.mojang.datafixers.DSL$TypeReference"), dynamic, int.class, int.class), fixer, kind, value, from, current);
        return call(dynamic.getMethod("getValue"), updated);
    }

    private static Object registryOps() throws ReflectiveOperationException {
        Object registry = call(craft("CraftRegistry").getMethod("getMinecraftRegistry"), null);
        return call(nms("core.HolderLookup$Provider").getMethod("createSerializationContext", dynamicOps()), registry, nbtOps());
    }
    private static Object result(Object result) throws ReflectiveOperationException {
        return call(type("com.mojang.serialization.DataResult").getMethod("getOrThrow"), result);
    }
    private static Class<?> dynamicOps() throws ClassNotFoundException { return type("com.mojang.serialization.DynamicOps"); }
    private static Object nbtOps() throws ReflectiveOperationException { return nms("nbt.NbtOps").getField("INSTANCE").get(null); }
    private static Class<?> nms(String name) throws ClassNotFoundException { return type("net.minecraft." + name); }
    private static Class<?> craft(String name) throws ClassNotFoundException { return type(Bukkit.getServer().getClass().getPackageName() + "." + name); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, Bukkit.getServer().getClass().getClassLoader()); }
    private static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); } catch (NoSuchMethodException absent) { return null; }
    }
    private static Object call(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalArgumentException("Native persistence operation failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) { throw unavailable(method.getName(), failure); }
    }
    private static IllegalStateException unavailable(String operation, Throwable cause) {
        return new IllegalStateException("Native " + operation + " is unavailable on this server; stored data was not modified", cause);
    }
}
