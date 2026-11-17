package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.packet.internal.BiomePackets;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** A small spatial layer composed over the optional snowy base view. Server thread only. */
final class ProjectedBiomes {
    private static final Map<UUID, View> VIEWS = new LinkedHashMap<>();
    private static int cursor;

    static Map<BlockPosition, NamespacedKey> cells(Player player) {
        View view = VIEWS.get(player.getUniqueId());
        return view != null && view.world.equals(player.getWorld().getUID()) ? view.cells : Map.of();
    }

    static boolean replace(Plugin owner, Player player, Map<BlockPosition, NamespacedKey> cells) {
        Objects.requireNonNull(owner); Objects.requireNonNull(player); Objects.requireNonNull(cells);
        if (cells.size() > 4096) throw new IllegalArgumentException("Biome overlay exceeds 4096 quart cells");
        Map<BlockPosition, NamespacedKey> next = Map.copyOf(cells);
        for (BlockPosition p : next.keySet()) {
            if ((p.x() & 3) != 0 || (p.y() & 3) != 0 || (p.z() & 3) != 0)
                throw new IllegalArgumentException("Biome coordinates must be aligned to four blocks");
            if (p.y() < player.getWorld().getMinHeight() || p.y() >= player.getWorld().getMaxHeight())
                throw new IllegalArgumentException("Biome coordinate outside world height");
        }
        if (keys(next).size() > 16) throw new IllegalArgumentException("Biome overlay exceeds 16 chunks");
        if (!ClientBiomes.supported() || !owner.isEnabled() || !player.isOnline()) return false;
        View view = VIEWS.get(player.getUniqueId());
        if (view != null && view.owner != owner) return false;
        if (next.isEmpty()) { clear(owner, player); return true; }
        try {
            if (view == null || !view.world.equals(player.getWorld().getUID())) {
                view = new View(owner, player);
                VIEWS.put(player.getUniqueId(), view);
            }
            if (!view.cells.equals(next)) {
                Set<BlockPosition> changed = new HashSet<>(view.cells.keySet()); changed.addAll(next.keySet());
                for (BlockPosition p : changed) if (!Objects.equals(view.cells.get(p), next.get(p))) view.pending.add(key(p));
                view.cells = next;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientBiomes", owner.getName(), "projection", failure);
            clear(owner, player); return false;
        }
    }

    static void loaded(Player player, UUID world, long key) {
        View view = VIEWS.get(player.getUniqueId());
        if (view != null && view.world.equals(world) && keys(view.cells).contains(key)) view.pending.add(key);
    }

    static void forget(Player player) { VIEWS.remove(player.getUniqueId()); }
    static void release(Plugin owner) {
        for (View view : List.copyOf(VIEWS.values())) if (view.owner == owner) clear(owner, view.player);
    }
    static void shutdown() { for (View view : List.copyOf(VIEWS.values())) clear(view.owner, view.player); }
    static void clear(Plugin owner, Player player) {
        View view = VIEWS.get(player.getUniqueId());
        if (view == null || view.owner != owner) return;
        VIEWS.remove(player.getUniqueId());
        if (!player.isOnline() || !view.world.equals(player.getWorld().getUID())) return;
        Set<Long> touched = keys(view.cells); touched.addAll(view.sent); touched.addAll(view.pending);
        try { ClientBiomes.sendBase(player, chunks(player, touched), view.adapter, Map.of()); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            ProviderFailures.report("ClientBiomes", owner.getName(), "restore-projection", failure);
        }
    }

    static void tick() {
        List<View> views = new ArrayList<>(VIEWS.values());
        if (!views.isEmpty()) Collections.rotate(views, -(cursor++ % views.size()));
        int budget = 64;
        for (View view : views) {
            if (!view.owner.isEnabled()) { clear(view.owner, view.player); continue; }
            if (!view.player.isOnline() || !view.world.equals(view.player.getWorld().getUID())) { forget(view.player); continue; }
            if (++view.ticks % 200 == 0) view.pending.addAll(keys(view.cells));
            if (budget <= 0) continue;
            Set<Long> batch = new LinkedHashSet<>();
            Iterator<Long> pending = view.pending.iterator();
            while (pending.hasNext() && batch.size() < Math.min(4, budget)) { batch.add(pending.next()); pending.remove(); }
            if (batch.isEmpty()) continue;
            try {
                ClientBiomes.sendBase(view.player, chunks(view.player, batch), view.adapter, view.cells);
                view.sent.addAll(batch);
                // Successfully restored chunks no longer belong to this overlay.
                view.sent.retainAll(keys(view.cells));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                ProviderFailures.report("ClientBiomes", view.owner.getName(), "send-projection", failure);
                clear(view.owner, view.player);
            }
            budget -= batch.size();
        }
    }

    private static List<Chunk> chunks(Player player, Set<Long> keys) {
        List<Chunk> result = new ArrayList<>();
        World world = player.getWorld();
        for (long key : keys) {
            int x = (int) key, z = (int) (key >> 32);
            if (ClientBiomes.sent(player, key) && world.isChunkLoaded(x, z)) result.add(world.getChunkAt(x, z));
        }
        return result;
    }
    private static long key(BlockPosition p) { return ((long)(p.z() >> 4) << 32) | ((p.x() >> 4) & 0xffffffffL); }
    private static Set<Long> keys(Map<BlockPosition, NamespacedKey> cells) {
        Set<Long> result = new LinkedHashSet<>(); for (BlockPosition p : cells.keySet()) result.add(key(p)); return result;
    }
    private static final class View {
        final Plugin owner; final Player player; final UUID world; final BiomePackets adapter;
        final Set<Long> pending = new LinkedHashSet<>(), sent = new LinkedHashSet<>();
        Map<BlockPosition, NamespacedKey> cells = Map.of(); int ticks;
        View(Plugin owner, Player player) throws ReflectiveOperationException {
            this.owner = owner; this.player = player; world = player.getWorld().getUID();
            adapter = new BiomePackets(player.getWorld(), Set.of());
        }
    }
}
