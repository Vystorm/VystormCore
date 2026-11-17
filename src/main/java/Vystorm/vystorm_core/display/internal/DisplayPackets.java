package Vystorm.vystorm_core.display.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

/**
 * Version-isolated packet adapter for client-only item frames and map data. Paper/Purpur 1.21.8 to 26.2 run with Mojang
 * mappings and share the same constructors for every packet used here (checked with javap against 1.21.8, 1.21.9,
 * 1.21.10, 1.21.11, 26.1.2 and 26.2); {@link #probe()} builds and encodes one packet of each kind, so any runtime where
 * a signature changes reports "unsupported" instead of failing later. Never touches a server entity or saved map.
 */
public final class DisplayPackets {
    private final Constructor<?> addEntity, setData, removeEntities, mapPacket, mapPatch, mapId;
    private final Method dataValue, asNmsCopy, send, playerHandle;
    private final Field connection;
    private final Object frameType, glowFrameType, zero, flags, item;
    private final Method nextIdWorld, nextIdPlain;

    public DisplayPackets() throws ReflectiveOperationException {
        Class<?> entityType = nms("world.entity.EntityType"), vec3 = nms("world.phys.Vec3");
        // 1.21.x and 26.1.x keep the constants on EntityType, 26.2 moved them to EntityTypes.
        Class<?> constants = entityType;
        try { entityType.getField("ITEM_FRAME"); } catch (NoSuchFieldException moved) { constants = nms("world.entity.EntityTypes"); }
        frameType = constants.getField("ITEM_FRAME").get(null);
        glowFrameType = constants.getField("GLOW_ITEM_FRAME").get(null);
        zero = vec3.getField("ZERO").get(null);
        addEntity = nms("network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, UUID.class,
                double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class);
        flags = staticField(nms("world.entity.Entity"), "DATA_SHARED_FLAGS_ID");
        item = staticField(nms("world.entity.decoration.ItemFrame"), "DATA_ITEM");
        dataValue = nms("network.syncher.SynchedEntityData$DataValue").getMethod("create", nms("network.syncher.EntityDataAccessor"), Object.class);
        setData = nms("network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);
        removeEntities = nms("network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
        Class<?> id = nms("world.level.saveddata.maps.MapId");
        mapId = id.getConstructor(int.class);
        mapPatch = nms("world.level.saveddata.maps.MapItemSavedData$MapPatch").getConstructor(int.class, int.class, int.class, int.class, byte[].class);
        mapPacket = nms("network.protocol.game.ClientboundMapItemDataPacket").getConstructor(id, byte.class, boolean.class, Optional.class, Optional.class);
        asNmsCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
        send = nms("server.network.ServerCommonPacketListenerImpl").getMethod("send", nms("network.protocol.Packet"));
        connection = nms("server.level.ServerPlayer").getField("connection");
        playerHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        // 1.21.x: UnsafeValues#nextEntityId(); 26.2: nextEntityId(World). Both take ids from the server's own counter,
        // so fake ids never collide with real entities.
        Method world = null, plain = null;
        for (Method m : Bukkit.getUnsafe().getClass().getMethods()) {
            if (!m.getName().equals("nextEntityId") || m.getReturnType() != int.class) continue;
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == World.class) world = m;
            if (m.getParameterCount() == 0) plain = m;
        }
        if (world == null && plain == null) throw new NoSuchMethodException("UnsafeValues.nextEntityId");
        nextIdWorld = world;
        nextIdPlain = plain;
    }

    public int nextEntityId(World world) throws ReflectiveOperationException {
        return nextIdWorld != null ? (int) nextIdWorld.invoke(Bukkit.getUnsafe(), world) : (int) nextIdPlain.invoke(Bukkit.getUnsafe());
    }

    /** Spawn packet of a wall item frame at a block, facing {@code facing}. */
    public Object spawnFrame(int entityId, UUID uuid, int x, int y, int z, BlockFace facing, boolean glow) throws ReflectiveOperationException {
        return addEntity.newInstance(entityId, uuid, (double) x, (double) y, (double) z, 0f, yaw(facing),
                glow ? glowFrameType : frameType, direction3d(facing), zero, 0d);
    }

    /** Metadata: optional invisibility and the filled map with {@code mapId}. */
    public Object frameData(int entityId, int mapId, boolean invisible) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>(2);
        if (invisible) values.add(dataValue.invoke(null, flags, (byte) 0x20));
        values.add(dataValue.invoke(null, item, asNmsCopy.invoke(null, mapItem(mapId))));
        return setData.newInstance(entityId, values);
    }

    public Object remove(int... entityIds) throws ReflectiveOperationException { return removeEntities.newInstance((Object) entityIds); }

    /** Map colours for a rectangle of a locked, scale-0 map without decorations. */
    public Object mapData(int id, int x, int y, int width, int height, byte[] colours) throws ReflectiveOperationException {
        Object patch = mapPatch.newInstance(x, y, width, height, colours);
        return mapPacket.newInstance(mapId.newInstance(id), (byte) 0, true, Optional.empty(), Optional.of(patch));
    }

    public void send(Player player, Object packet) throws ReflectiveOperationException {
        send.invoke(connection.get(playerHandle.invoke(player)), packet);
    }

    /**
     * Builds and encodes one packet of each kind with the server's own codecs (as the network layer would). Returns the
     * uncompressed payload sizes in bytes; throws when anything does not fit this runtime.
     */
    public Map<String, Integer> probe(World world) throws ReflectiveOperationException {
        Object registries = nms("server.MinecraftServer").getMethod("registryAccess").invoke(nms("server.MinecraftServer").getMethod("getServer").invoke(null));
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
        Constructor<?> friendly = nms("network.RegistryFriendlyByteBuf").getConstructor(byteBuf, nms("core.RegistryAccess"));
        Method buffer = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer");
        Method encode = nms("network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class);
        Method readable = byteBuf.getMethod("readableBytes");
        int id = nextEntityId(world);
        Map<String, Object> packets = new LinkedHashMap<>();
        packets.put("spawn", spawnFrame(id, UUID.randomUUID(), 0, 64, 0, BlockFace.NORTH, true));
        packets.put("data", frameData(id, Integer.MAX_VALUE, true));
        packets.put("map-full", mapData(Integer.MAX_VALUE, 0, 0, 128, 128, new byte[128 * 128]));
        packets.put("map-empty", mapData(Integer.MAX_VALUE, 0, 0, 1, 1, new byte[1]));
        packets.put("remove", remove(id));
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (var entry : packets.entrySet()) {
            Object codec = entry.getValue().getClass().getField("STREAM_CODEC").get(null);
            Object buf = friendly.newInstance(buffer.invoke(null), registries);
            encode.invoke(codec, buf, entry.getValue());
            sizes.put(entry.getKey(), (int) readable.invoke(buf));
        }
        return sizes;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack mapItem(int mapId) {
        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) stack.getItemMeta();
        meta.setMapId(mapId);
        stack.setItemMeta(meta);
        return stack;
    }

    static int direction3d(BlockFace face) {
        return switch (face) {
            case DOWN -> 0; case UP -> 1; case NORTH -> 2; case SOUTH -> 3; case WEST -> 4; case EAST -> 5;
            default -> throw new IllegalArgumentException("Frames face a cardinal direction, not " + face);
        };
    }

    private static float yaw(BlockFace face) {
        return switch (face) { case SOUTH -> 0f; case WEST -> 90f; case NORTH -> 180f; case EAST -> 270f; default -> 0f; };
    }

    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Class<?> nms(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
}
