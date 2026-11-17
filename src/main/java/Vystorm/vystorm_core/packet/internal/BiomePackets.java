package Vystorm.vystorm_core.packet.internal;

import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import Vystorm.vystorm_core.visual.BlockPosition;

/** Version-isolated, read-only 26.2 adapter. Never writes a server biome or shared packet. */
public final class BiomePackets {
    /** Structural probe in addition to the verified wire-format version allowlist. */
    public static boolean available() { return Features.AVAILABLE; }
    private static final class Features {
        static final boolean AVAILABLE = detect();
        private static boolean detect() {
            try {
                Class<?> data = type("network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData");
                data.getConstructor(type("world.level.chunk.LevelChunk"));
                data.getConstructor(type("world.level.ChunkPos"), byte[].class);
                data.getMethod("buffer"); data.getMethod("pos");
                type("core.RegistryAccess").getMethod("lookupOrThrow", type("resources.ResourceKey"));
                type("world.level.chunk.status.ChunkStatus").getField("FULL");
                return true;
            } catch (ReflectiveOperationException | LinkageError unavailable) { return false; }
        }
    }
    private final Constructor<?> dataFromChunk, dataFromBytes, packet;
    private final Method chunkHandle, dataBuffer, dataPos, send, getId, getKey, size;
    private final Object fullStatus, registry;
    private final int bits;
    private final int[] winter;
    private final Map<String, Integer> biomeIds = new HashMap<>();
    public BiomePackets(World world, Set<String> excluded) throws ReflectiveOperationException {
        Class<?> chunk = type("world.level.chunk.LevelChunk"), status = type("world.level.chunk.status.ChunkStatus");
        Class<?> data = type("network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData");
        Class<?> reg = type("core.Registry"), idMap = type("core.IdMap");
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object access = type("world.level.Level").getMethod("registryAccess").invoke(level);
        Object biomeKey = type("core.registries.Registries").getField("BIOME").get(null);
        registry = type("core.RegistryAccess").getMethod("lookupOrThrow", type("resources.ResourceKey")).invoke(access, biomeKey);
        size = idMap.getMethod("size"); getId = idMap.getMethod("getId", Object.class);
        getKey = reg.getMethod("getKey", Object.class);
        int count = (int) size.invoke(registry);
        bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(count - 1));
        winter = new int[count];
        int snow = -1;
        for (Object biome : (Iterable<?>) registry) if ("minecraft:snowy_plains".equals(getKey.invoke(registry, biome).toString())) snow = (int)getId.invoke(registry, biome);
        if (snow < 0) throw new IllegalStateException("Missing snowy_plains biome");
        for (Object biome : (Iterable<?>) registry) {
            int id = (int)getId.invoke(registry, biome);
            String key = getKey.invoke(registry, biome).toString();
            biomeIds.put(key, id);
            String shortKey = key.substring(key.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            winter[id] = excluded.contains(key) || excluded.contains(shortKey) ? id : snow;
        }
        fullStatus = status.getField("FULL").get(null);
        chunkHandle = Class.forName("org.bukkit.craftbukkit.CraftChunk").getMethod("getHandle", status);
        dataFromChunk = data.getConstructor(chunk);
        dataFromBytes = data.getConstructor(type("world.level.ChunkPos"), byte[].class);
        dataBuffer = data.getMethod("buffer"); dataPos = data.getMethod("pos");
        packet = type("network.protocol.game.ClientboundChunksBiomesPacket").getConstructor(List.class);
        send = type("server.network.ServerCommonPacketListenerImpl").getMethod("send", type("network.protocol.Packet"));
    }
    public void send(Player viewer, Collection<Chunk> chunks, boolean snowy) throws ReflectiveOperationException {
        send(viewer, chunks, snowy, Map.of());
    }
    public void send(Player viewer, Collection<Chunk> chunks, boolean snowy,
            Map<BlockPosition, NamespacedKey> overrides) throws ReflectiveOperationException {
        List<Object> values = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (!chunk.isLoaded() || !Vystorm.vystorm_core.compat.ClientEvents.sent(viewer, Vystorm.vystorm_core.compat.PlatformCompat.getChunkKey(chunk))) continue;
            values.add(biomeData(chunk, snowy, overrides));
        }
        if (values.isEmpty()) return;
        Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        Object connection = handle.getClass().getField("connection").get(handle);
        send.invoke(connection, packet.newInstance(values));
    }
    private Object biomeData(Chunk chunk, boolean snowy, Map<BlockPosition, NamespacedKey> overrides)
            throws ReflectiveOperationException {
        Object original = dataFromChunk.newInstance(chunkHandle.invoke(chunk, fullStatus));
        if (!snowy && overrides.isEmpty()) return original;
        byte[] source = (byte[])dataBuffer.invoke(original);
        byte[] changed = BiomePaletteCodec.remapCells(source, bits, (cell, id) -> {
            if (id < 0 || id >= winter.length) throw new IllegalArgumentException("Unknown biome ID " + id);
            BlockPosition position = new BlockPosition(chunk.getX() * 16 + (cell & 3) * 4,
                    chunk.getWorld().getMinHeight() + (cell >> 4) * 4,
                    chunk.getZ() * 16 + ((cell >> 2) & 3) * 4);
            NamespacedKey biome = overrides.get(position);
            if (biome != null) {
                Integer replacement = biomeIds.get(biome.toString());
                if (replacement == null) throw new IllegalArgumentException("Unknown biome " + biome);
                return replacement;
            }
            return snowy ? winter[id] : id;
        });
        return dataFromBytes.newInstance(dataPos.invoke(original), changed);
    }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
}
