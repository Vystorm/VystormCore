package Vystorm.vystorm_core.display;

import Vystorm.vystorm_core.display.internal.MapTiles;
import Vystorm.vystorm_core.display.internal.WallGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A client-only wall of map item frames created by {@link ClientDisplays#mapWall}. No server entity and no server map
 * exist: every viewer within {@link Spec#viewDistance()} gets fake item frames and map colours by packets. All methods
 * require the server thread. Pixels are map colour bytes (as {@code MapPalette}), row-major, {@code width*128} wide and
 * {@code height*128} high; Core keeps the passed array without copying, so pass a fresh array for every change.
 * @since 0.44.0
 */
public final class MapWall {
    /**
     * Where and how the wall hangs. {@code (x, y, z)} is the block of the top-left frame as seen from the front; tiles run
     * to the viewer's right and downwards. {@code facing} is the side the frames face (NORTH, SOUTH, EAST or WEST).
     */
    public record Spec(World world, int x, int y, int z, BlockFace facing, int width, int height, boolean glow, boolean invisible, double viewDistance) {
        public Spec {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(facing, "facing");
            if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH && facing != BlockFace.EAST && facing != BlockFace.WEST)
                throw new IllegalArgumentException("Map walls face NORTH, SOUTH, EAST or WEST");
            if (width < 1 || height < 1 || width > 32 || height > 32) throw new IllegalArgumentException("Map walls are 1x1 to 32x32 tiles");
            if (!(viewDistance >= 1 && viewDistance <= 256)) throw new IllegalArgumentException("viewDistance must be 1..256 blocks");
        }

        /** Glowing, invisible frames seen within 48 blocks. */
        public static Spec of(World world, int x, int y, int z, BlockFace facing, int width, int height) {
            return new Spec(world, x, y, z, facing, width, height, true, true, 48);
        }

        public Spec glow(boolean glow) { return new Spec(world, x, y, z, facing, width, height, glow, invisible, viewDistance); }
        public Spec invisible(boolean invisible) { return new Spec(world, x, y, z, facing, width, height, glow, invisible, viewDistance); }
        public Spec viewDistance(double blocks) { return new Spec(world, x, y, z, facing, width, height, glow, invisible, blocks); }
        public int pixelWidth() { return width * MapTiles.SIZE; }
        public int pixelHeight() { return height * MapTiles.SIZE; }
    }

    /**
     * A left ({@code attack}) or right click on a frame of the wall. {@code pixelX/Y} are wall pixels from the viewer's look
     * ray at the moment of the click (the tile centre if the ray just misses).
     */
    public record Click(Player player, MapWall wall, int tileX, int tileY, int pixelX, int pixelY, boolean attack) {}

    static final class Viewer {
        final Player player;
        final boolean[] shown;
        final int[][] pending;
        int pendingCount, cursor;

        Viewer(Player player, int tiles) { this.player = player; shown = new boolean[tiles]; pending = new int[tiles][]; }

        void mark(int tile, int[] rect) {
            if (rect == null || !shown[tile]) return;
            if (pending[tile] == null) pendingCount++;
            pending[tile] = MapTiles.union(pending[tile], rect);
        }

        void markFull(int tile) { mark(tile, MapTiles.full()); }
    }

    final Plugin owner;
    final Spec spec;
    final int[] entityIds, mapIds;
    final UUID[] uuids;
    final int[][] blocks;
    final long[] chunks;
    /** Spawn and metadata packets per tile, built on first use and re-sent to every viewer (they never change). */
    final Object[] spawnPackets, dataPackets;
    byte[] shared;
    final Map<UUID, byte[]> own = new HashMap<>();
    final Map<UUID, Viewer> viewers = new LinkedHashMap<>();
    Consumer<Click> clicks;
    boolean removed;

    MapWall(Plugin owner, Spec spec, int[] entityIds, int[] mapIds) {
        this.owner = owner;
        this.spec = spec;
        this.entityIds = entityIds;
        this.mapIds = mapIds;
        int tiles = spec.width * spec.height;
        uuids = new UUID[tiles];
        blocks = new int[tiles][];
        chunks = new long[tiles];
        spawnPackets = new Object[tiles];
        dataPackets = new Object[tiles];
        for (int i = 0; i < tiles; i++) {
            uuids[i] = UUID.randomUUID();
            blocks[i] = WallGeometry.tile(spec.x, spec.y, spec.z, spec.facing.getModX(), spec.facing.getModZ(), i % spec.width, i / spec.width);
            chunks[i] = ((long) (blocks[i][2] >> 4) << 32) | ((blocks[i][0] >> 4) & 0xffffffffL);
        }
    }

    public Plugin owner() { return owner; }
    public Spec spec() { return spec; }
    public boolean removed() { return removed; }

    /** The client-side map id of a tile (row-major); from Core's reserved range, never a real map. */
    public int mapId(int tile) { return mapIds[tile]; }

    /** Content shown to every viewer without own content. Only the changed rectangle of each tile is sent. */
    public void shared(byte[] pixels) {
        ClientDisplays.thread();
        check(pixels);
        if (removed) return;
        byte[] old = shared;
        shared = pixels;
        for (Viewer viewer : viewers.values()) if (!own.containsKey(viewer.player.getUniqueId())) mark(viewer, old, pixels);
    }

    /** Content only this viewer sees (replaces the shared content for them). */
    public void content(Player viewer, byte[] pixels) {
        ClientDisplays.thread();
        Objects.requireNonNull(viewer);
        check(pixels);
        if (removed) return;
        byte[] old = own.get(viewer.getUniqueId());
        if (old == null) old = shared;
        own.put(viewer.getUniqueId(), pixels);
        Viewer state = viewers.get(viewer.getUniqueId());
        if (state != null) mark(state, old, pixels);
    }

    /** Drops the viewer's own content; they see the shared content again. */
    public void clearContent(Player viewer) {
        ClientDisplays.thread();
        byte[] old = own.remove(viewer.getUniqueId());
        Viewer state = viewers.get(viewer.getUniqueId());
        if (old != null && state != null) mark(state, old, shared);
    }

    /** Whether the viewer currently has something to see: own content or shared content. */
    public boolean hasContent(Player viewer) { return shared != null || own.containsKey(viewer.getUniqueId()); }

    public boolean hasOwnContent(Player viewer) { return own.containsKey(viewer.getUniqueId()); }

    /** Players who currently get this wall (in range, same world). */
    public List<Player> viewers() { return viewers.values().stream().map(v -> v.player).toList(); }

    public boolean isViewing(Player player) { return viewers.containsKey(player.getUniqueId()); }

    /** Click callback (server thread). Replaces an earlier one. */
    public void onClick(Consumer<Click> handler) { ClientDisplays.thread(); clicks = handler; }

    /** Sends the whole wall to this viewer again (frames and all map colours). */
    public void resend(Player player) {
        ClientDisplays.thread();
        Viewer state = viewers.get(player.getUniqueId());
        if (state != null) java.util.Arrays.fill(state.shown, false);
        ClientDisplays.recheck(player);
    }

    /** Removes the frames from every viewer and frees the ids. Idempotent. */
    public void remove() { ClientDisplays.thread(); ClientDisplays.remove(this); }

    private void mark(Viewer viewer, byte[] old, byte[] next) {
        int stride = spec.pixelWidth();
        for (int i = 0; i < entityIds.length; i++) {
            if (!viewer.shown[i]) continue;
            if (next == null) continue;
            if (old == null) viewer.markFull(i);
            else viewer.mark(i, MapTiles.changed(old, next, stride, i % spec.width, i / spec.width));
        }
    }

    byte[] source(Viewer viewer) {
        byte[] mine = own.get(viewer.player.getUniqueId());
        return mine != null ? mine : shared;
    }

    List<Integer> tilesIn(long chunk) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < chunks.length; i++) if (chunks[i] == chunk) result.add(i);
        return result;
    }

    private void check(byte[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length != spec.pixelWidth() * spec.pixelHeight())
            throw new IllegalArgumentException("Expected " + spec.pixelWidth() + "x" + spec.pixelHeight() + " pixels, got " + pixels.length);
    }
}
