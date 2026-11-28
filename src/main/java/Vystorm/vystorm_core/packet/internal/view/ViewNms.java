package Vystorm.vystorm_core.packet.internal.view;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Every server-internal name of the packet view, looked up by reflection in one place (Paper, Purpur and Spigot 26.2
 * and 26.3, Mojang names at runtime on all of them). Moved from VystormAntiXray's {@code platform.Nms}. Only public members are used: nothing here writes a field or makes a
 * member accessible. The one difference between the platforms is how a player's connection is reached
 * ({@link #channel}). A missing name throws while the instance is built
 * ({@link #load}); the self-test ({@link ViewSelfTest}) then proves the formats with real chunks. Callers treat
 * any exception from here as "view off". Internal: not part of the public API.
 */
public final class ViewNms {
    static final String P = "net.minecraft.network.protocol.game.";

    // packet classes (recognised on the Netty thread)
    final Class<?> chunkPacket, lightPacket, sectionPacket, blockUpdate, blockEntityData, blockEvent, blockDestruction,
            levelEvent, sound, particles, addEntity, forgetChunk, playerInfoRemove;
    // the client's world and the pipeline (Netty thread)
    final Class<?> respawnPacket, loginPacket, startConfiguration, bundlePacket, packetBase;
    private final Method respawnInfo, loginInfo, spawnDimension, levelDimension, subPackets;
    private final Constructor<?> bundleCtor;
    /** Play packet ids of the chunk, light and section packets, and the highest clientbound configuration packet id. */
    final int chunkId, lightId, sectionId, maxConfigurationId;
    private final Method blockUpdatePos, blockEntityPos, blockEventPos, blockDestructionPos, levelEventPos, levelEventGlobal,
            soundX, soundY, soundZ, particleX, particleY, particleZ, addEntityX, addEntityY, addEntityZ, addEntityId,
            forgetPos, chunkPosX, chunkPosZ, profileIds, posX, posY, posZ, chunkPacketX, chunkPacketZ;
    // world access (server thread)
    private final Method worldHandle, playerHandle, chunkNow, getSection, lightEngine, blockEntities, updatePacket, sectionPosOf;
    private final Field connection, channelField;
    /** Paper and Purpur: the public field from a player's packet listener to its connection. Null on Spigot (protected there). */
    private final Field connectionInner;
    /** Spigot: the server, its connection list and each connection's listener, all public (see {@link ConnectionLookup}). */
    private final Method minecraftServer, serverConnection, connections, packetListener;
    private final Method send;
    private final Constructor<?> sectionUpdateCtor, lightUpdateCtor, chunkPacketCtor, chunkPosCtor, shortSet;
    private final Method shortSetAdd;
    // block states
    private final Object stateRegistry;
    private final Method registrySize, registryById, registryId, isAir, fluidState, fluidEmpty, defaultState;
    private final Class<?> blocks;

    private ViewNms() throws ReflectiveOperationException {
        chunkPacket = cls(P + "ClientboundLevelChunkWithLightPacket");
        lightPacket = cls(P + "ClientboundLightUpdatePacket");
        sectionPacket = cls(P + "ClientboundSectionBlocksUpdatePacket");
        blockUpdate = cls(P + "ClientboundBlockUpdatePacket");
        blockEntityData = cls(P + "ClientboundBlockEntityDataPacket");
        blockEvent = cls(P + "ClientboundBlockEventPacket");
        blockDestruction = cls(P + "ClientboundBlockDestructionPacket");
        levelEvent = cls(P + "ClientboundLevelEventPacket");
        sound = cls(P + "ClientboundSoundPacket");
        particles = cls(P + "ClientboundLevelParticlesPacket");
        addEntity = cls(P + "ClientboundAddEntityPacket");
        forgetChunk = cls(P + "ClientboundForgetLevelChunkPacket");
        playerInfoRemove = cls(P + "ClientboundPlayerInfoRemovePacket");
        respawnPacket = cls(P + "ClientboundRespawnPacket");
        loginPacket = cls(P + "ClientboundLoginPacket");
        startConfiguration = cls(P + "ClientboundStartConfigurationPacket");
        bundlePacket = cls(P + "ClientboundBundlePacket");
        packetBase = cls("net.minecraft.network.protocol.Packet");
        respawnInfo = respawnPacket.getMethod("commonPlayerSpawnInfo");
        loginInfo = loginPacket.getMethod("commonPlayerSpawnInfo");
        spawnDimension = cls(P + "CommonPlayerSpawnInfo").getMethod("dimension");
        levelDimension = cls("net.minecraft.world.level.Level").getMethod("dimension");
        subPackets = cls("net.minecraft.network.protocol.BundlePacket").getMethod("subPackets");
        bundleCtor = bundlePacket.getConstructor(Iterable.class);
        Map<String, Integer> play = clientboundIds("net.minecraft.network.protocol.game.GameProtocols");
        chunkId = id(play, "level_chunk_with_light");
        lightId = id(play, "light_update");
        sectionId = id(play, "section_blocks_update");
        int max = -1;
        for (int v : clientboundIds("net.minecraft.network.protocol.configuration.ConfigurationProtocols").values()) max = Math.max(max, v);
        maxConfigurationId = max;
        chunkPacketX = method(chunkPacket, "getX", "x");
        chunkPacketZ = method(chunkPacket, "getZ", "z");
        blockUpdatePos = blockUpdate.getMethod("getPos");
        blockEntityPos = blockEntityData.getMethod("getPos");
        blockEventPos = blockEvent.getMethod("getPos");
        blockDestructionPos = blockDestruction.getMethod("getPos");
        levelEventPos = levelEvent.getMethod("getPos");
        levelEventGlobal = levelEvent.getMethod("isGlobalEvent");
        soundX = sound.getMethod("getX");
        soundY = sound.getMethod("getY");
        soundZ = sound.getMethod("getZ");
        particleX = method(particles, "getX", "x");
        particleY = method(particles, "getY", "y");
        particleZ = method(particles, "getZ", "z");
        addEntityX = addEntity.getMethod("getX");
        addEntityY = addEntity.getMethod("getY");
        addEntityZ = addEntity.getMethod("getZ");
        addEntityId = addEntity.getMethod("getId");
        forgetPos = forgetChunk.getMethod("pos");
        Class<?> chunkPos = cls("net.minecraft.world.level.ChunkPos");
        chunkPosX = chunkPos.getMethod("x");
        chunkPosZ = chunkPos.getMethod("z");
        chunkPosCtor = chunkPos.getConstructor(int.class, int.class);
        profileIds = playerInfoRemove.getMethod("profileIds");
        Class<?> vec3i = cls("net.minecraft.core.Vec3i");
        posX = vec3i.getMethod("getX");
        posY = vec3i.getMethod("getY");
        posZ = vec3i.getMethod("getZ");

        worldHandle = cls("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
        playerHandle = cls("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        Class<?> serverLevel = cls("net.minecraft.server.level.ServerLevel"), level = cls("net.minecraft.world.level.Level");
        Class<?> chunkCache = cls("net.minecraft.server.level.ServerChunkCache");
        chunkSource = serverLevel.getMethod("getChunkSource"); // returns the ServerChunkCache (maybe through a bridge method)
        chunkNow = chunkCache.getMethod("getChunkNow", int.class, int.class);
        Class<?> chunkAccess = cls("net.minecraft.world.level.chunk.ChunkAccess"), levelChunk = cls("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> section = cls("net.minecraft.world.level.chunk.LevelChunkSection");
        getSection = chunkAccess.getMethod("getSection", int.class);
        lightEngine = level.getMethod("getLightEngine");
        blockEntities = levelChunk.getMethod("getBlockEntities");
        updatePacket = cls("net.minecraft.world.level.block.entity.BlockEntity").getMethod("getUpdatePacket");
        Class<?> sectionPos = cls("net.minecraft.core.SectionPos");
        sectionPosOf = sectionPos.getMethod("of", int.class, int.class, int.class);
        Class<?> serverPlayer = cls("net.minecraft.server.level.ServerPlayer");
        connection = serverPlayer.getField("connection");
        Class<?> common = cls("net.minecraft.server.network.ServerCommonPacketListenerImpl");
        Field inner;
        try { inner = common.getField("connection"); } // public on Paper and Purpur
        catch (NoSuchFieldException spigot) { inner = null; } // protected on Spigot: found through the server's connection list
        connectionInner = inner;
        send = common.getMethod("send", cls("net.minecraft.network.protocol.Packet"));
        Class<?> networkConnection = cls("net.minecraft.network.Connection");
        channelField = networkConnection.getField("channel");
        if (inner == null) {
            Class<?> server = cls("net.minecraft.server.MinecraftServer");
            minecraftServer = server.getMethod("getServer");
            serverConnection = server.getMethod("getConnection");
            connections = cls("net.minecraft.server.network.ServerConnectionListener").getMethod("getConnections");
            packetListener = networkConnection.getMethod("getPacketListener");
        } else { minecraftServer = null; serverConnection = null; connections = null; packetListener = null; }
        Class<?> shortSetType = cls("it.unimi.dsi.fastutil.shorts.ShortSet");
        Class<?> shortArraySet = cls("it.unimi.dsi.fastutil.shorts.ShortOpenHashSet");
        shortSet = shortArraySet.getConstructor();
        shortSetAdd = shortSetType.getMethod("add", short.class);
        sectionUpdateCtor = sectionPacket.getConstructor(sectionPos, shortSetType, section);
        Class<?> lightEngineType = cls("net.minecraft.world.level.lighting.LevelLightEngine");
        lightUpdateCtor = lightPacket.getConstructor(chunkPos, lightEngineType, BitSet.class, BitSet.class);
        chunkPacketCtor = chunkPacket.getConstructor(levelChunk, lightEngineType, BitSet.class, BitSet.class);

        Class<?> block = cls("net.minecraft.world.level.block.Block"), state = cls("net.minecraft.world.level.block.state.BlockState");
        stateRegistry = block.getField("BLOCK_STATE_REGISTRY").get(null);
        Class<?> idMapper = stateRegistry.getClass();
        registrySize = idMapper.getMethod("size");
        registryById = idMapper.getMethod("byId", int.class);
        registryId = block.getMethod("getId", state);
        Class<?> stateBase = cls("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        isAir = stateBase.getMethod("isAir");
        fluidState = stateBase.getMethod("getFluidState");
        fluidEmpty = cls("net.minecraft.world.level.material.FluidState").getMethod("isEmpty");
        defaultState = block.getMethod("defaultBlockState");
        blocks = cls("net.minecraft.world.level.block.Blocks");
    }

    private final Method chunkSource;

    /** Builds the accessor; throws with the first missing name. */
    public static ViewNms load() throws ReflectiveOperationException { return new ViewNms(); }

    /** Packet ids of a protocol's clientbound side by packet type name (e.g. "minecraft:light_update"). */
    static Map<String, Integer> clientboundIds(String protocols) throws ReflectiveOperationException {
        Class<?> visitor = cls("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Method details = cls("net.minecraft.network.ProtocolInfo$DetailsProvider").getMethod("details");
        Method list = cls("net.minecraft.network.ProtocolInfo$Details").getMethod("listPackets", visitor);
        Method typeId = cls("net.minecraft.network.protocol.PacketType").getMethod("id");
        Object template = cls(protocols).getField("CLIENTBOUND_TEMPLATE").get(null);
        Map<String, Integer> out = new java.util.HashMap<>();
        Object v = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class<?>[] {visitor}, (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return m.getName().equals("equals") ? proxy == a[0] : m.getName().equals("hashCode") ? System.identityHashCode(proxy) : "ids";
            if (a != null && a.length == 2 && a[1] instanceof Integer i) out.put(String.valueOf(typeId.invoke(a[0])), i);
            return null;
        });
        list.invoke(details.invoke(template), v);
        if (out.isEmpty()) throw new NoSuchFieldException(protocols + ": no packet ids");
        return out;
    }

    static int id(Map<String, Integer> ids, String name) throws NoSuchFieldException {
        Integer i = ids.get("minecraft:" + name);
        if (i == null) throw new NoSuchFieldException("packet id of " + name);
        return i;
    }

    static Class<?> cls(String name) throws ClassNotFoundException { return Class.forName(name, false, ViewNms.class.getClassLoader()); }

    static Method method(Class<?> c, String... names) throws NoSuchMethodException {
        for (String n : names) try { return c.getMethod(n); } catch (NoSuchMethodException next) { }
        throw new NoSuchMethodException(c.getName() + "." + String.join("|", names));
    }

    static Object call(Method m, Object target, Object... args) {
        try { return m.invoke(target, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(m.getName(), e.getCause() != null ? e.getCause() : e); }
    }

    // ---- packets on the Netty thread ------------------------------------------------------------------------------

    /** x, y, z of a BlockPos. */
    public int[] pos(Object blockPos) { return new int[] {(int) call(posX, blockPos), (int) call(posY, blockPos), (int) call(posZ, blockPos)}; }

    /** Chunk x and z of a chunk packet (26.2 getX/getZ, 26.3 record x/z). */
    public int[] chunkPacketPos(Object p) { return new int[] {(int) call(chunkPacketX, p), (int) call(chunkPacketZ, p)}; }

    public int[] blockUpdatePos(Object p) { return pos(call(blockUpdatePos, p)); }

    public int[] blockEntityPos(Object p) { return pos(call(blockEntityPos, p)); }

    public int[] blockEventPos(Object p) { return pos(call(blockEventPos, p)); }

    public int[] blockDestructionPos(Object p) { return pos(call(blockDestructionPos, p)); }

    /** Null for a global level event (heard everywhere, e.g. a wither spawn). */
    public int[] levelEventPos(Object p) { return (boolean) call(levelEventGlobal, p) ? null : pos(call(levelEventPos, p)); }

    public double[] soundPos(Object p) { return new double[] {(double) call(soundX, p), (double) call(soundY, p), (double) call(soundZ, p)}; }

    public double[] particlePos(Object p) { return new double[] {(double) call(particleX, p), (double) call(particleY, p), (double) call(particleZ, p)}; }

    public double[] addEntityPos(Object p) { return new double[] {(double) call(addEntityX, p), (double) call(addEntityY, p), (double) call(addEntityZ, p)}; }

    public int addEntityId(Object p) { return (int) call(addEntityId, p); }

    public int[] forgetPos(Object p) { Object cp = call(forgetPos, p); return new int[] {(int) call(chunkPosX, cp), (int) call(chunkPosZ, cp)}; }

    @SuppressWarnings("unchecked")
    public List<UUID> profileIds(Object p) { return (List<UUID>) call(profileIds, p); }

    /** The dimension key a login or respawn packet names. */
    public Object dimensionOf(Object loginOrRespawn) {
        Object info = call(loginOrRespawn.getClass() == respawnPacket ? respawnInfo : loginInfo, loginOrRespawn);
        return call(spawnDimension, info);
    }

    /** The loaded world with this dimension key, null if none (any thread; only a fallback). */
    public World worldByKey(Object dimensionKey) {
        try {
            for (World w : new ArrayList<>(org.bukkit.Bukkit.getWorlds())) if (dimension(w) == dimensionKey) return w;
        } catch (RuntimeException e) { }
        return null;
    }

    /** The server's dimension key of a world (the object login and respawn packets carry). */
    public Object dimension(World w) { return call(levelDimension, level(w)); }

    /** The packets in a bundle. */
    public List<Object> subPackets(Object bundle) {
        List<Object> out = new ArrayList<>();
        for (Object o : (Iterable<?>) call(subPackets, bundle)) out.add(o);
        return out;
    }

    /** A bundle of these packets. */
    public Object bundle(List<Object> packets) {
        try { return bundleCtor.newInstance(packets); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    // ---- world and players (server thread) ------------------------------------------------------------------------

    public Object level(World w) { return call(worldHandle, w); }

    public Object chunkNow(Object level, int cx, int cz) { return call(chunkNow, call(chunkSource, level), cx, cz); }

    public Object section(Object levelChunk, int index) { return call(getSection, levelChunk, index); }

    public Object lightEngine(Object level) { return call(lightEngine, level); }

    /** Whether a player's connection is found through the server's connection list (Spigot) instead of a public field. */
    public boolean connectionByList() { return connectionInner == null; }

    /** The Netty channel of a player's connection; throws when it is not found (the player then stays unfiltered). */
    public Channel channel(Player p) {
        try {
            Object handle = call(playerHandle, p);
            Object listener = connection.get(handle);
            Object network;
            if (connectionInner != null) network = connectionInner.get(listener);
            else {
                List<?> all = (List<?>) call(connections, call(serverConnection, call(minecraftServer, null)));
                network = ConnectionLookup.find(all, c -> call(packetListener, c), listener);
                if (network == null) throw new IllegalStateException("no connection of " + p.getName() + " in the server's connection list");
            }
            return (Channel) channelField.get(network);
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /** Sends a packet to the player through the server's own connection (and so through this plugin's filter). */
    public void send(Player p, Object packet) {
        try { call(send, connection.get(call(playerHandle, p)), packet); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /** A section blocks update with the TRUE states of the given cells (y << 8 | z << 4 | x) of one section. */
    public Object sectionUpdate(Object levelChunk, int cx, int sectionY, int cz, int sectionIndex, int[] cells) {
        try {
            Object set = shortSet.newInstance();
            for (int k : cells) {
                int x = k & 15, z = (k >> 4) & 15, y = k >> 8;
                call(shortSetAdd, set, (short) (x << 8 | z << 4 | y));
            }
            return sectionUpdateCtor.newInstance(call(sectionPosOf, null, cx, sectionY, cz), set, section(levelChunk, sectionIndex));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** A light update with the server's real light of the given light sections (bit = world section + 1). */
    public Object lightUpdate(Object level, int cx, int cz, BitSet sky, BitSet block) {
        try { return lightUpdateCtor.newInstance(chunkPosCtor.newInstance(cx, cz), lightEngine(level), sky, block); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** A full chunk packet as the server would send it (goes through the filter when sent). */
    public Object chunkPacket(Object level, Object levelChunk) {
        try { return chunkPacketCtor.newInstance(levelChunk, lightEngine(level), null, null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** Update packets of the block entities at the given packed positions ({@code x, y, z}) of a chunk. */
    public List<Object> blockEntityPackets(Object levelChunk, java.util.function.Predicate<int[]> wanted) {
        List<Object> out = new ArrayList<>();
        @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) call(blockEntities, levelChunk);
        for (Map.Entry<Object, Object> e : new ArrayList<>(map.entrySet())) {
            if (!wanted.test(pos(e.getKey()))) continue;
            Object packet = call(updatePacket, e.getValue());
            if (packet != null) out.add(packet);
        }
        return out;
    }

    // ---- block states ---------------------------------------------------------------------------------------------

    /** Air and fluid flags of every global block state id. */
    public Vystorm.vystorm_core.packet.internal.wire.WireTables tables() {
        int n = (int) call(registrySize, stateRegistry);
        boolean[] air = new boolean[n], fluid = new boolean[n];
        for (int i = 0; i < n; i++) {
            Object s = call(registryById, stateRegistry, i);
            if (s == null) { air[i] = true; continue; }
            air[i] = (boolean) call(isAir, s);
            fluid[i] = !(boolean) call(fluidEmpty, call(fluidState, s));
        }
        return new Vystorm.vystorm_core.packet.internal.wire.WireTables(air, fluid);
    }

    /** The network id of a Bukkit block data (CraftBlockData.getState, then the state registry). Server thread. */
    public int wireId(org.bukkit.block.data.BlockData data) {
        try {
            Object state = data.getClass().getMethod("getState").invoke(data);
            return (int) call(registryId, null, state);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("no server state for " + data.getAsString(), e); }
    }

    /** The global id of a block's default state by its field name in Blocks (e.g. "STONE"). */
    public int defaultId(String blockField) {
        try { return (int) call(registryId, null, call(defaultState, blocks.getField(blockField).get(null))); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(blockField, e); }
    }

    /** Paper's own anti-xray of a world: the controller's class name ("ChunkPacketBlockController" when off). */
    public String paperAntiXray(World w) {
        try {
            Field f = level(w).getClass().getField("chunkPacketBlockController");
            Object c = f.get(level(w));
            return c == null ? "none" : c.getClass().getSimpleName();
        } catch (NoSuchFieldException missing) { return ViewPlatform.kind() == ViewPlatform.Kind.SPIGOT ? "none" : "unknown"; } // Spigot has no anti-xray of its own
        catch (ReflectiveOperationException | RuntimeException e) { return "unknown"; }
    }

    // ---- codec access for the self-test --------------------------------------------------------------------------

    public Object registryAccess() throws ReflectiveOperationException {
        Object server = cls("org.bukkit.craftbukkit.CraftServer").getMethod("getServer").invoke(org.bukkit.Bukkit.getServer());
        return server.getClass().getMethod("registryAccess").invoke(server);
    }

    /** The packet's body as the server's codec writes it (no packet id). */
    public byte[] encode(Object packet) throws ReflectiveOperationException {
        Object codec = packet.getClass().getField("STREAM_CODEC").get(null);
        ByteBuf raw = Unpooled.buffer();
        try {
            Object buf = friendly(raw);
            Method enc = cls("net.minecraft.network.codec.StreamEncoder").getMethod("encode", Object.class, Object.class);
            enc.invoke(codec, buf, packet);
            byte[] out = new byte[raw.readableBytes()];
            raw.readBytes(out);
            return out;
        } finally { raw.release(); }
    }

    /** Decodes a body with the server's codec; throws when bytes are left over. */
    public Object decode(Class<?> type, byte[] body) throws ReflectiveOperationException {
        Object codec = type.getField("STREAM_CODEC").get(null);
        ByteBuf raw = Unpooled.wrappedBuffer(body);
        Object buf = friendly(raw);
        Method dec = cls("net.minecraft.network.codec.StreamDecoder").getMethod("decode", Object.class);
        Object out = dec.invoke(codec, buf);
        if (raw.readableBytes() != 0) throw new IllegalStateException(type.getSimpleName() + ": " + raw.readableBytes() + " bytes left after decode");
        return out;
    }

    private Object friendly(ByteBuf raw) throws ReflectiveOperationException {
        Class<?> rfb = cls("net.minecraft.network.RegistryFriendlyByteBuf");
        return rfb.getConstructor(ByteBuf.class, cls("net.minecraft.core.RegistryAccess")).newInstance(raw, registryAccess());
    }

    public Object stateAt(Object section, int x, int y, int z) throws ReflectiveOperationException {
        return section.getClass().getMethod("getBlockState", int.class, int.class, int.class).invoke(section, x, y, z);
    }

    public int stateId(Object state) { return (int) call(registryId, null, state); }
}
