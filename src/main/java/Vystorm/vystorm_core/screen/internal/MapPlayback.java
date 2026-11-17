package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * GIF animations on screen map widgets (since 0.59.0): loads a GIF once (off the main thread, cached by key), converts its
 * frames to map colours for a {@link ScreenWidget.MapGrid}, and plays them on a session's map widget at the GIF's pace
 * (at least one tick per frame), looping until the session closes or the task is cancelled.
 */
public final class MapPlayback {
    /** A loaded animation: frames for one grid. */
    public record Animation(ScreenWidget.MapGrid grid, List<MapImages.Frame> frames) {}

    private static final Map<String, CompletableFuture<Animation>> CACHE = new ConcurrentHashMap<>();

    private MapPlayback() {}

    /** Map grid for an image size: rows by height, columns by aspect ratio, 1..4 x 2..3 tiles. */
    public static ScreenWidget.MapGrid grid(int width, int height) {
        double aspect = width / (double) Math.max(1, height);
        int rows = height >= 200 ? 3 : 2;
        int columns = (int) Math.round(rows * aspect * ScreenWidget.MapGrid.TILE_HEIGHT / ScreenWidget.MapGrid.TILE_WIDTH);
        return new ScreenWidget.MapGrid(Math.max(1, Math.min(4, columns)), rows);
    }

    /**
     * The animation for {@code key}, loading it with {@code source} on first use (asynchronously). {@code grid} null =
     * choose by the GIF's size. Completes exceptionally when the GIF cannot be read.
     */
    public static CompletableFuture<Animation> load(Plugin plugin, String key, Supplier<InputStream> source, ScreenWidget.MapGrid grid, int maxFrames) {
        return CACHE.computeIfAbsent(key, k -> {
            CompletableFuture<Animation> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (InputStream in = source.get()) {
                    if (in == null) throw new java.io.FileNotFoundException(key);
                    List<Integer> delays = new ArrayList<>();
                    List<BufferedImage> images = MapImages.gifFrames(in, maxFrames, delays);
                    if (images.isEmpty()) throw new java.io.IOException("no frames in " + key);
                    ScreenWidget.MapGrid g = grid != null ? grid : grid(images.getFirst().getWidth(), images.getFirst().getHeight());
                    List<MapImages.Frame> frames = new ArrayList<>(images.size());
                    for (int i = 0; i < images.size(); i++)
                        frames.add(new MapImages.Frame(MapImages.toMap(images.get(i), g.pixelWidth(), g.pixelHeight(), true), delays.get(i)));
                    future.complete(new Animation(g, List.copyOf(frames)));
                } catch (Exception failure) {
                    CACHE.remove(k);
                    future.completeExceptionally(failure);
                }
            });
            return future;
        });
    }

    /** Plays {@code frames} on {@code widgetId} of the session; returns the task (cancel it to stop). */
    public static BukkitTask play(Plugin plugin, ScreenSession session, String widgetId, List<MapImages.Frame> frames) {
        int[] index = { 0 };
        long[] due = { 0 };
        BukkitTask[] task = { null };
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!session.isOpen()) { task[0].cancel(); return; }
            long now = System.currentTimeMillis();
            if (now < due[0]) return;
            MapImages.Frame frame = frames.get(index[0]);
            session.setMap(widgetId, frame.pixels());
            due[0] = now + frame.delayMillis() - 25;   // the timer runs every tick; keep the GIF's pace
            index[0] = (index[0] + 1) % frames.size();
        }, 2L, 1L);
        return task[0];
    }

    /** Forgets cached animations of a plugin (key prefix), e.g. when it is disabled. */
    public static void forget(String keyPrefix) { CACHE.keySet().removeIf(k -> k.startsWith(keyPrefix)); }
}
