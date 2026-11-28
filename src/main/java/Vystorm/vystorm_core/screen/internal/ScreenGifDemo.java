package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /screen demo gif <name>} (since 0.59.0): plays {@code plugins/vystorm_core/screens/<name>.gif} on a map widget
 * in a PINNED screen. Frames are decoded and converted to map colours off the main thread, then sent one after another
 * at the GIF's speed (at least one tick per frame) in a loop until the screen closes. Each frame goes only to the viewer.
 */
final class ScreenGifDemo {
    static final int MAX_FRAMES = 300;
    private final Plugin core;
    private final Lang lang;
    private final Screens screens;

    ScreenGifDemo(Plugin core, Lang lang, Screens screens) {
        this.core = core;
        this.lang = lang;
        this.screens = screens;
    }

    static File folder(Plugin core) { return new File(core.getDataFolder(), "screens"); }

    /** GIF names in the screens folder (without {@code .gif}). */
    static List<String> names(Plugin core) {
        File[] files = folder(core).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gif"));
        if (files == null) return List.of();
        return Arrays.stream(files).map(f -> f.getName().substring(0, f.getName().length() - 4)).sorted().toList();
    }

    /** Map grid for an image: rows by height, columns by aspect ratio, 1..4 x 1..3 tiles. */
    static ScreenWidget.MapGrid grid(int width, int height) {
        double aspect = width / (double) Math.max(1, height);
        int rows = height >= 200 ? 3 : 2;
        int columns = (int) Math.round(rows * aspect * ScreenWidget.MapGrid.TILE_HEIGHT / ScreenWidget.MapGrid.TILE_WIDTH);
        columns = Math.max(1, Math.min(4, columns));
        return new ScreenWidget.MapGrid(columns, rows);
    }

    void open(Player player, String name) {
        folder(core).mkdirs();
        if (name == null || name.isBlank()) {
            lang.send(player, "screen.demo.gif.list", "folder", folder(core).getPath(), "names", names(core).isEmpty() ? "–" : String.join(", ", names(core)));
            return;
        }
        File file = new File(folder(core), name.replaceAll("[^A-Za-z0-9_.-]", "") + ".gif");
        if (!file.isFile()) { lang.send(player, "screen.demo.gif.missing", "name", name, "folder", folder(core).getPath()); return; }
        lang.send(player, "screen.demo.gif.loading", "name", name);
        Bukkit.getScheduler().runTaskAsynchronously(core, () -> {
            List<Integer> delays = new ArrayList<>();
            List<BufferedImage> images;
            try (InputStream in = new FileInputStream(file)) {
                images = MapImages.gifFrames(in, MAX_FRAMES, delays);
            } catch (Exception failure) {
                Bukkit.getScheduler().runTask(core, () -> lang.send(player, "screen.demo.gif.broken", "name", name, "error", String.valueOf(failure.getMessage())));
                return;
            }
            if (images.isEmpty()) return;
            ScreenWidget.MapGrid grid = grid(images.getFirst().getWidth(), images.getFirst().getHeight());
            List<MapImages.Frame> frames = new ArrayList<>(images.size());
            for (int i = 0; i < images.size(); i++) {
                frames.add(new MapImages.Frame(MapImages.toMap(images.get(i), grid.pixelWidth(), grid.pixelHeight(), true), delays.get(i)));
            }
            Bukkit.getScheduler().runTask(core, () -> { if (player.isOnline()) play(player, name, grid, frames); });
        });
    }

    private void play(Player player, String name, ScreenWidget.MapGrid grid, List<MapImages.Frame> frames) {
        ScreenProfile profile = screens.profile(player);
        double margin = 0.04, head = 0.1, foot = 0.1;
        double maxW = Math.min(profile.viewWidth() * 0.8, 2.2), maxH = profile.viewHeight() * 0.8 - head - foot;
        double ratio = grid.pixelWidth() / (double) grid.pixelHeight();
        double mapW = Math.min(maxW - 2 * margin, maxH * ratio), mapH = mapW / ratio;
        double panelW = Math.max(0.9, mapW + 2 * margin), panelH = head + mapH + foot;
        double mapU = (panelW - mapW) / 2;
        ScreenLayout layout = new ScreenLayout(panelW, panelH, List.of(
                ScreenWidget.label("title", margin, 0.02, panelW - 2 * margin - 0.25, 0.06, lang.component(player, "screen.demo.gif.title", "name", name))
                        .withAlign(ScreenWidget.Align.START),
                ScreenWidget.button("close", panelW - margin - 0.22, 0.02, 0.22, 0.06, lang.component(player, "screen.demo.close")).withTextHeight(0.034),
                ScreenWidget.map("gif", mapU, head, mapW, mapH, grid, lang.component(player, "screen.demo.map.placeholder")),
                ScreenWidget.label("info", margin, head + mapH + 0.02, panelW - 2 * margin, 0.045,
                        lang.component(player, "screen.demo.gif.info", "frames", frames.size(), "grid", grid.columns() + "x" + grid.rows()))
                        .withAlign(ScreenWidget.Align.START)));
        BukkitTask[] task = { null };
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:demo_gif", layout)
                .mode(ScreenSpec.Mode.PINNED)
                .onInput((session, input) -> {
                    if (input instanceof ScreenInput.Click click && "close".equals(click.widgetId())) session.close();
                    if (input instanceof ScreenInput.Closed && task[0] != null) task[0].cancel();
                })
                .build();
        ScreenOpenResult result = screens.open(player, spec);
        if (!result.opened()) { if (ScreenRuntime.silentRefusal(result.refusedKey())) lang.send(player, result.refusedKey()); return; }
        ScreenSession session = screens.session(player).orElse(null);
        if (session == null) return;
        int[] index = { 0 };
        long[] due = { 0 };
        task[0] = Bukkit.getScheduler().runTaskTimer(core, () -> {
            if (!session.isOpen()) { task[0].cancel(); return; }
            long now = System.currentTimeMillis();
            if (now < due[0]) return;
            MapImages.Frame frame = frames.get(index[0]);
            session.setMap("gif", frame.pixels());
            due[0] = now + frame.delayMillis() - 25;   // the timer runs every tick; keep the GIF's pace
            index[0] = (index[0] + 1) % frames.size();
        }, 2L, 1L);
    }
}
