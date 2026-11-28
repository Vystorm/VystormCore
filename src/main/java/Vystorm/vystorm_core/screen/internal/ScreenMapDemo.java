package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /screen demo map} (since 0.59.0): the world around the player as a 2x2-tile map canvas in a PINNED screen.
 * Sneak zooms in, jump zooms out (both claimed, so the close button or {@code /screen close} closes), a click on the
 * map shows the world coordinates under the pointer. Only loaded chunks are drawn; the rest stays dark.
 */
final class ScreenMapDemo {
    static final int[] SCALES = { 1, 2, 4, 8 };
    static final ScreenWidget.MapGrid GRID = new ScreenWidget.MapGrid(2, 2);
    static final double PANEL_W = 1.3, PANEL_H = 0.84, MAP_U = 0.04, MAP_V = 0.11, MAP_W = 0.7;
    static final double MAP_H = MAP_W * GRID.pixelHeight() / GRID.pixelWidth();
    /** Map colour byte 0 is transparent (unloaded chunks show the dark widget box). */
    static final byte NONE = 0;

    private final Lang lang;
    private final Screens screens;

    ScreenMapDemo(Lang lang, Screens screens) {
        this.lang = lang;
        this.screens = screens;
    }

    static ScreenLayout layout(Lang lang, Player viewer, int scale) {
        double x = 0.8, w = 0.46;
        return new ScreenLayout(PANEL_W, PANEL_H, List.of(
                ScreenWidget.label("title", MAP_U, 0.02, 0.7, 0.07, lang.component(viewer, "screen.demo.map.title")).withAlign(ScreenWidget.Align.START),
                ScreenWidget.map("map", MAP_U, MAP_V, MAP_W, MAP_H, GRID, lang.component(viewer, "screen.demo.map.placeholder")),
                ScreenWidget.label("zoom", x, MAP_V, w, 0.06, lang.component(viewer, "screen.demo.map.zoom", "scale", scale)),
                ScreenWidget.button("in", x, MAP_V + 0.09, w, 0.08, lang.component(viewer, "screen.demo.map.in")),
                ScreenWidget.button("out", x, MAP_V + 0.19, w, 0.08, lang.component(viewer, "screen.demo.map.out")),
                ScreenWidget.label("info", x, MAP_V + 0.3, w, 0.06, lang.component(viewer, "screen.demo.map.hint")),
                ScreenWidget.button("close", x, MAP_V + MAP_H - 0.08, w, 0.08, lang.component(viewer, "screen.demo.close"))));
    }

    void open(Player player) {
        int[] zoom = { 1 };
        int cx = player.getLocation().getBlockX(), cz = player.getLocation().getBlockZ();
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:demo_map", layout(lang, player, SCALES[zoom[0]]))
                .mode(ScreenSpec.Mode.PINNED)
                .claim(ScreenInput.Key.SNEAK, ScreenInput.Key.JUMP)
                .onInput((session, input) -> handle(session, input, zoom, cx, cz))
                .build();
        ScreenOpenResult result = screens.open(player, spec);
        if (!result.opened()) { if (ScreenRuntime.silentRefusal(result.refusedKey())) lang.send(player, result.refusedKey()); return; }
        lang.send(player, "screen.demo.map.opened", "mode", result.mode().name().toLowerCase(java.util.Locale.ROOT));
        screens.session(player).ifPresent(s -> redraw(s, zoom[0], cx, cz));
    }

    private void handle(ScreenSession session, ScreenInput input, int[] zoom, int cx, int cz) {
        Player player = session.player();
        if (input instanceof ScreenInput.KeyInput key && key.pressed()) {
            if (key.key() == ScreenInput.Key.SNEAK) zoom(session, zoom, -1, cx, cz);
            else if (key.key() == ScreenInput.Key.JUMP) zoom(session, zoom, 1, cx, cz);
            return;
        }
        if (!(input instanceof ScreenInput.Click click) || click.widgetId() == null) return;
        switch (click.widgetId()) {
            case "in" -> zoom(session, zoom, -1, cx, cz);
            case "out" -> zoom(session, zoom, 1, cx, cz);
            case "close" -> session.close();
            case "map" -> {
                ScreenWidget map = session.spec().layout().widgets().stream().filter(w -> w.id().equals("map")).findFirst().orElseThrow();
                int[] px = map.mapPixel(click.u(), click.v(), PANEL_W, PANEL_H);
                int[] world = worldAt(px[0], px[1], SCALES[zoom[0]], cx, cz);
                session.setText("info", lang.component(player, "screen.demo.map.clicked", "x", world[0], "z", world[1]));
            }
            default -> { }
        }
    }

    private void zoom(ScreenSession session, int[] zoom, int step, int cx, int cz) {
        int next = Math.max(0, Math.min(SCALES.length - 1, zoom[0] + step));
        if (next == zoom[0]) return;
        zoom[0] = next;
        session.setText("zoom", lang.component(session.player(), "screen.demo.map.zoom", "scale", SCALES[next]));
        redraw(session, next, cx, cz);
    }

    private static void redraw(ScreenSession session, int zoom, int cx, int cz) {
        session.setMap("map", render(session.player().getWorld(), cx, cz, SCALES[zoom], new HashMap<>()));
    }

    /** World block {x, z} shown at canvas pixel {@code (px, py)}; the player stands at the canvas centre. */
    static int[] worldAt(int px, int py, int scale, int cx, int cz) {
        return new int[] { cx + (px - GRID.pixelWidth() / 2) * scale, cz + (py - GRID.pixelHeight() / 2) * scale };
    }

    /**
     * Vanilla-like top view: each pixel is the map colour of the highest block, brighter when higher than the pixel to
     * the north and darker when lower. Unloaded chunks stay transparent; a red cross marks the player.
     */
    static byte[] render(World world, int cx, int cz, int scale, Map<Integer, Byte> palette) {
        int w = GRID.pixelWidth(), h = GRID.pixelHeight();
        byte[] out = new byte[w * h];
        int[] north = new int[w];
        for (int px = 0; px < w; px++) north[px] = Integer.MIN_VALUE;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int[] at = worldAt(px, py, scale, cx, cz);
                if (!world.isChunkLoaded(at[0] >> 4, at[1] >> 4)) { out[py * w + px] = NONE; north[px] = Integer.MIN_VALUE; continue; }
                int y = world.getHighestBlockYAt(at[0], at[1]);
                Material top = world.getBlockAt(at[0], y, at[1]).getType();
                int rgb = colour(world, at[0], y, at[1], top);
                int shade = north[px] == Integer.MIN_VALUE || y == north[px] ? 220 : y > north[px] ? 255 : 180;
                north[px] = y;
                out[py * w + px] = match(palette, rgb, shade);
            }
        }
        byte red = match(palette, 0xE03030, 255);
        int mx = w / 2, my = h / 2;
        for (int d = -2; d <= 2; d++) {
            out[my * w + mx + d] = red;
            out[(my + d) * w + mx] = red;
        }
        return out;
    }

    private static int colour(World world, int x, int y, int z, Material top) {
        try {
            org.bukkit.Color c = world.getBlockAt(x, y, z).getBlockData().getMapColor();
            return c.asRGB();
        } catch (RuntimeException | LinkageError older) {
            return top == Material.WATER ? 0x4040FF : 0x7FB238;
        }
    }

    private static byte match(Map<Integer, Byte> palette, int rgb, int shade) {
        return palette.computeIfAbsent((rgb & 0xFFFFFF) | shade << 24, key -> {
            int r = (rgb >> 16 & 0xFF) * shade / 255, g = (rgb >> 8 & 0xFF) * shade / 255, b = (rgb & 0xFF) * shade / 255;
            return Vystorm.vystorm_core.screen.MapColors.table().nearest(r, g, b);   // = MapPalette.matchColor
        });
    }
}
