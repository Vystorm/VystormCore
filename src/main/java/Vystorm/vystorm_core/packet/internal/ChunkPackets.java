package Vystorm.vystorm_core.packet.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Version-isolated adapter for client-only chunk packets on Paper/Purpur 1.21.8 to 26.3 (Mojang mappings).
 *
 * <p>A client-only chunk is built as a detached {@code LevelChunk} (never added to the server's chunk map, no
 * ticking, no saving): blocks go into its sections, biomes come from a {@code BiomeResolver}, the heightmaps are
 * primed, and the server's own {@code ClientboundLevelChunkWithLightPacket} constructor serializes it, so the wire
 * format of every version comes from the server itself. The constructor is called with empty light masks (it then
 * reads nothing from the server's light engine) and the sky light computed by {@link ChunkLight} is put into the
 * packet's light data. Differences between versions handled here: 26.3 made the packet and its light data records
 * ({@code lightData()}, {@code skyYMask()} instead of {@code getLightData()}, {@code getSkyYMask()}) and dropped the
 * climate sampler from {@code BiomeResolver}/{@code fillBiomesFromNoise}.
 *
 * <p>Everything except {@link #send} may run on any thread; the adapter itself is built on the server thread.
 */
public final class ChunkPackets {
    private final Constructor<?> chunkCtor, chunkPos, packetCtor, forgetCtor;
    private final Method levelHandle, getSection, setBlockState, fillBiomes, getHeightmaps, prime, blockState;
    private final Method lightEngineOf, lightData, skyMask, emptySkyMask, skyUpdates;
    private final Method send, playerHandle;
    private final Field connection;
    private final Class<?> resolverType, typesClass;
    private final boolean resolverHasSampler;
    private final Method encode, readableBytes, readBytes;
    private final Constructor<?> friendlyBuf;
    private final Method unpooled;
    private final Object registryAccess, codec;
    /** biome registry lookups per world: biome key -> holder */
    private final Map<java.util.UUID, Map<String, Object>> biomes = new ConcurrentHashMap<>();
    private final Map<BlockData, Object> states = new ConcurrentHashMap<>();

    public ChunkPackets() throws ReflectiveOperationException {
        Class<?> level = nms("world.level.Level"), levelChunk = nms("world.level.chunk.LevelChunk"), pos = nms("world.level.ChunkPos");
        Class<?> chunkAccess = nms("world.level.chunk.ChunkAccess"), section = nms("world.level.chunk.LevelChunkSection");
        Class<?> state = nms("world.level.block.state.BlockState"), lightEngine = nms("world.level.lighting.LevelLightEngine");
        chunkCtor = levelChunk.getConstructor(level, pos);
        chunkPos = pos.getConstructor(int.class, int.class);
        lightEngineOf = level.getMethod("getLightEngine");
        levelHandle = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
        getSection = chunkAccess.getMethod("getSection", int.class);
        setBlockState = section.getMethod("setBlockState", int.class, int.class, int.class, state, boolean.class);
        resolverType = nms("world.level.biome.BiomeResolver");
        Method fill = null;
        for (Method m : chunkAccess.getMethods()) {
            if (!m.getName().equals("fillBiomesFromNoise") || m.getParameterCount() < 1 || m.getParameterTypes()[0] != resolverType) continue;
            if (fill == null || m.getParameterCount() < fill.getParameterCount()) fill = m;
        }
        if (fill == null) throw new NoSuchMethodException("ChunkAccess.fillBiomesFromNoise");
        fillBiomes = fill;
        resolverHasSampler = fill.getParameterCount() == 2;
        getHeightmaps = chunkAccess.getMethod("getHeightmaps");
        typesClass = nms("world.level.levelgen.Heightmap$Types");
        prime = nms("world.level.levelgen.Heightmap").getMethod("primeHeightmaps", chunkAccess, java.util.Set.class);
        blockState = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData").getMethod("getState");
        Class<?> packet = nms("network.protocol.game.ClientboundLevelChunkWithLightPacket");
        packetCtor = packet.getConstructor(levelChunk, lightEngine, BitSet.class, BitSet.class, boolean.class);
        lightData = method(packet, "getLightData", "lightData");
        Class<?> data = nms("network.protocol.game.ClientboundLightUpdatePacketData");
        skyMask = method(data, "getSkyYMask", "skyYMask");
        emptySkyMask = method(data, "getEmptySkyYMask", "emptySkyYMask");
        skyUpdates = method(data, "getSkyUpdates", "skyUpdates");
        forgetCtor = nms("network.protocol.game.ClientboundForgetLevelChunkPacket").getConstructor(pos);
        send = nms("server.network.ServerCommonPacketListenerImpl").getMethod("send", nms("network.protocol.Packet"));
        connection = nms("server.level.ServerPlayer").getField("connection");
        playerHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        Class<?> server = nms("server.MinecraftServer");
        registryAccess = server.getMethod("registryAccess").invoke(server.getMethod("getServer").invoke(null));
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
        friendlyBuf = nms("network.RegistryFriendlyByteBuf").getConstructor(byteBuf, nms("core.RegistryAccess"));
        unpooled = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer");
        encode = nms("network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class);
        readableBytes = byteBuf.getMethod("readableBytes");
        readBytes = byteBuf.getMethod("readBytes", byte[].class);
        codec = packet.getField("STREAM_CODEC").get(null);
    }

    /** A built packet with its measured size: payload bytes as the codec writes them, and deflated (an estimate of the wire size). */
    public record Built(Object packet, int bytes, int compressed) {}

    /**
     * Builds the packet of a client-only chunk.
     *
     * @param blocks  palette index per block ({@code ((y - minY) * 16 + z) * 16 + x}), 0 = air
     * @param palette block data per palette index (index 0 unused)
     * @param biome   biome key of block column (x, z) as "namespace:key", or null for plains
     */
    public Built build(World world, int chunkX, int chunkZ, int sections, short[] blocks, List<BlockData> palette, int[] opacity,
                       java.util.function.BiFunction<Integer, Integer, String> biome) throws ReflectiveOperationException {
        Object level = levelHandle.invoke(world);
        Object pos = chunkPos.newInstance(chunkX, chunkZ);
        Object chunk = chunkCtor.newInstance(level, pos);
        Object[] st = new Object[palette.size()];
        for (int i = 1; i < st.length; i++) st[i] = state(palette.get(i));
        for (int s = 0; s < sections; s++) {
            Object sec = null;
            int base = s * 4096;
            for (int i = 0; i < 4096; i++) {
                int p = blocks[base + i];
                if (p == 0) continue;
                if (sec == null) sec = getSection.invoke(chunk, s);
                setBlockState.invoke(sec, i & 15, i >> 8, (i >> 4) & 15, st[p], false);
            }
        }
        Map<String, Object> holders = holders(world, level);
        Object plains = holders.get("minecraft:plains");
        Object[] cols = new Object[16];
        for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
            String key = biome.apply(qx * 4 + 1, qz * 4 + 1);
            Object h = key == null ? null : holders.get(key);
            cols[qz * 4 + qx] = h != null ? h : plains;
        }
        int qx0 = chunkX * 4, qz0 = chunkZ * 4;
        Object resolver = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[]{resolverType}, (proxy, m, args) -> {
            if (m.getName().equals("getNoiseBiome")) {
                int qx = Math.floorMod((Integer) args[0] - qx0, 4), qz = Math.floorMod((Integer) args[2] - qz0, 4);
                return cols[qz * 4 + qx];
            }
            return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "VystormCore client chunk biomes";
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
        if (resolverHasSampler) fillBiomes.invoke(chunk, resolver, null); else fillBiomes.invoke(chunk, resolver);
        primeHeightmaps(chunk);
        Object lightEngine = lightEngineOf.invoke(level);
        Object packet = packetCtor.newInstance(chunk, lightEngine, new BitSet(), new BitSet(), false);
        ChunkLight.Sky sky = ChunkLight.sky(blocks, opacity, sections);
        Object data = lightData.invoke(packet);
        BitSet mask = (BitSet) skyMask.invoke(data), empty = (BitSet) emptySkyMask.invoke(data);
        @SuppressWarnings("unchecked") List<byte[]> updates = (List<byte[]>) skyUpdates.invoke(data);
        mask.clear(); empty.clear(); updates.clear();
        mask.or(sky.mask()); empty.or(sky.empty()); updates.addAll(sky.data());
        byte[] wire = encode(packet);
        return new Built(packet, wire.length, deflated(wire));
    }

    private void primeHeightmaps(Object chunk) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"}) EnumSet types = EnumSet.noneOf((Class) typesClass);
            for (Object e : (Collection<?>) getHeightmaps.invoke(chunk)) {
                @SuppressWarnings("unchecked") Object k = ((Map.Entry<Object, Object>) e).getKey();
                types.add(k);
            }
            if (!types.isEmpty()) prime.invoke(null, chunk, types);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the client primes missing heightmaps itself; only rain and snow rendering would differ
        }
    }

    private Object state(BlockData data) throws ReflectiveOperationException {
        Object s = states.get(data);
        if (s == null) { s = blockState.invoke(data); states.put(data, s); }
        return s;
    }

    private Map<String, Object> holders(World world, Object level) throws ReflectiveOperationException {
        Map<String, Object> m = biomes.get(world.getUID());
        if (m != null) return m;
        Object access = nms("world.level.Level").getMethod("registryAccess").invoke(level);
        Object key = nms("core.registries.Registries").getField("BIOME").get(null);
        Object registry = nms("core.RegistryAccess").getMethod("lookupOrThrow", nms("resources.ResourceKey")).invoke(access, key);
        Method list = nms("core.HolderLookup$RegistryLookup").getMethod("listElements");
        Method name = nms("core.Holder").getMethod("getRegisteredName");
        Map<String, Object> out = new HashMap<>();
        for (Object h : ((java.util.stream.Stream<?>) list.invoke(registry)).toList()) out.put((String) name.invoke(h), h);
        if (!out.containsKey("minecraft:plains")) throw new IllegalStateException("no minecraft:plains biome");
        Map<String, Object> frozen = Map.copyOf(out);
        biomes.put(world.getUID(), frozen);
        return frozen;
    }

    /** the packet as the network layer writes it (before compression) */
    byte[] encode(Object packet) throws ReflectiveOperationException {
        Object buf = friendlyBuf.newInstance(unpooled.invoke(null), registryAccess);
        encode.invoke(codec, buf, packet);
        byte[] out = new byte[(int) readableBytes.invoke(buf)];
        readBytes.invoke(buf, (Object) out);
        return out;
    }

    static int deflated(byte[] raw) {
        Deflater d = new Deflater();
        try {
            d.setInput(raw); d.finish();
            byte[] buf = new byte[8192];
            int n = 0;
            while (!d.finished()) n += d.deflate(buf);
            return n;
        } finally { d.end(); }
    }

    public Object forget(int chunkX, int chunkZ) throws ReflectiveOperationException { return forgetCtor.newInstance(chunkPos.newInstance(chunkX, chunkZ)); }

    /** server thread */
    public void send(Player player, Object packet) throws ReflectiveOperationException {
        send.invoke(connection.get(playerHandle.invoke(player)), packet);
    }

    /** Builds and encodes one small client-only chunk far outside the world; throws when this runtime does not fit. */
    public Built probe(World world) throws ReflectiveOperationException {
        int sections = (world.getMaxHeight() - world.getMinHeight()) >> 4;
        short[] blocks = new short[sections * 4096];
        for (int i = 0; i < 256; i++) blocks[i] = 1;
        BlockData stone = Bukkit.createBlockData(org.bukkit.Material.STONE);
        Built b = build(world, 1_874_999, 1_874_999, sections, blocks, List.of(stone, stone), new int[]{0, 15}, (x, z) -> "minecraft:plains");
        forget(0, 0);
        return b;
    }

    private static Method method(Class<?> owner, String... names) throws NoSuchMethodException {
        for (String n : names) try { return owner.getMethod(n); } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(owner.getSimpleName() + "." + String.join("/", names));
    }

    private static Class<?> nms(String name) throws ClassNotFoundException { return Class.forName("net.minecraft." + name); }
}
