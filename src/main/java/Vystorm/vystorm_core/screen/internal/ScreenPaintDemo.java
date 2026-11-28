package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.screen.MapColors;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * {@code /screen demo paint} (0.72.0): a 2x2-tile canvas that is painted with a held right button, three
 * colours and Clear. It uses all three canvas additions: {@code trackPress} (right button down/up),
 * {@code trackPointer} (one pointer sample per tick while over the canvas) and the changed-rectangle map sender (the
 * info line shows how many patches and pixels went to the client). A left click on the canvas paints one dot.
 */
final class ScreenPaintDemo {
    static final ScreenWidget.MapGrid GRID = new ScreenWidget.MapGrid(2, 2);
    static final double PANEL_W = 1.3, PANEL_H = 0.84, MAP_U = 0.04, MAP_V = 0.11, MAP_W = 0.7;
    static final double MAP_H = MAP_W * GRID.pixelHeight() / GRID.pixelWidth();
    static final int W = GRID.pixelWidth(), H = GRID.pixelHeight();
    /** Brush radius in canvas pixels (3 x 3 stamp). */
    static final int BRUSH = 1;
    static final String[] COLOURS = { "red", "green", "blue" };
    static final int[] RGB = { 0xDC0000, 0x007C00, 0x3737DC };

    private final Lang lang;
    private final Screens screens;

    ScreenPaintDemo(Lang lang, Screens screens) {
        this.lang = lang;
        this.screens = screens;
    }

    /** What one open demo remembers. */
    static final class Canvas {
        final byte[] pixels = new byte[W * H];
        final byte background;
        byte colour;
        int chosen;
        boolean down;
        int lastX = -1, lastY = -1;

        Canvas(MapColors table) {
            background = table.nearest(0xFFFFFF);
            colour = table.nearest(RGB[0]);
            java.util.Arrays.fill(pixels, background);
        }

        /** A 3x3 stamp at every pixel of the line from the last point to {@code (x, y)} (Bresenham). */
        void strokeTo(int x, int y) {
            if (lastX < 0) { stamp(x, y); lastX = x; lastY = y; return; }
            int x0 = lastX, y0 = lastY, dx = Math.abs(x - x0), dy = -Math.abs(y - y0);
            int sx = x0 < x ? 1 : -1, sy = y0 < y ? 1 : -1, err = dx + dy;
            while (true) {
                stamp(x0, y0);
                if (x0 == x && y0 == y) break;
                int e2 = 2 * err;
                if (e2 >= dy) { err += dy; x0 += sx; }
                if (e2 <= dx) { err += dx; y0 += sy; }
            }
            lastX = x;
            lastY = y;
        }

        void stamp(int cx, int cy) {
            for (int y = cy - BRUSH; y <= cy + BRUSH; y++) for (int x = cx - BRUSH; x <= cx + BRUSH; x++)
                if (x >= 0 && y >= 0 && x < W && y < H) pixels[y * W + x] = colour;
        }

        void lift() { lastX = -1; lastY = -1; }

        void clear() { java.util.Arrays.fill(pixels, background); lift(); }
    }

    static ScreenLayout layout(Lang lang, Player viewer, int chosen) {
        double x = 0.8, w = 0.46;
        MapColors table = MapColors.table();
        List<ScreenWidget> widgets = new java.util.ArrayList<>(List.of(
                ScreenWidget.label("title", MAP_U, 0.02, 0.7, 0.07, lang.component(viewer, "screen.demo.paint.title")).withAlign(ScreenWidget.Align.START),
                ScreenWidget.map("canvas", MAP_U, MAP_V, MAP_W, MAP_H, GRID, lang.component(viewer, "screen.demo.paint.placeholder"))));
        for (int i = 0; i < COLOURS.length; i++) {
            int rgb = table.rgb(table.nearest(RGB[i]));
            widgets.add(ScreenWidget.button(COLOURS[i], x, MAP_V + i * 0.09, w, 0.08, lang.component(viewer, "screen.demo.paint." + COLOURS[i]))
                    .withFill(0xD0000000 | rgb).withHighlight(0xF0E3B04B).withSelected(i == chosen));
        }
        widgets.add(ScreenWidget.button("clear", x, MAP_V + 0.29, w, 0.08, lang.component(viewer, "screen.demo.paint.clear")));
        widgets.add(ScreenWidget.label("info", x, MAP_V + 0.39, w, 0.12, lang.component(viewer, "screen.demo.paint.hint")));
        widgets.add(ScreenWidget.button("close", x, MAP_V + MAP_H - 0.08, w, 0.08, lang.component(viewer, "screen.demo.close")));
        return new ScreenLayout(PANEL_W, PANEL_H, widgets);
    }

    /**
     * {@code holes}: the C0 experiment for 26.3 clients: the maps are shown even where Core switches them off, with the
     * panel cut around them (see {@code ScreenRuntime.mapHoles}). Only for this one screen.
     */
    void open(Player player, boolean holes) {
        ScreenRuntime.get().mapHoles(player, holes);
        try { open(player); }
        finally { ScreenRuntime.get().mapHoles(player, false); }
    }

    void open(Player player) {
        Canvas canvas = new Canvas(MapColors.table());
        ScreenSpec spec = ScreenSpec.builder("vystorm_core:demo_paint", layout(lang, player, 0))
                .mode(ScreenSpec.Mode.PINNED)
                .onInput((session, input) -> handle(session, input, canvas))
                .build();
        ScreenOpenResult result = screens.open(player, spec);
        if (!result.opened()) { if (ScreenRuntime.silentRefusal(result.refusedKey())) lang.send(player, result.refusedKey()); return; }
        lang.send(player, "screen.demo.paint.opened", "mode", result.mode().name().toLowerCase(java.util.Locale.ROOT));
        screens.session(player).ifPresent(s -> {
            s.trackPointer("canvas", true);
            s.trackPress(true);
            s.setMap("canvas", canvas.pixels);
        });
    }

    private ScreenWidget canvasWidget(ScreenSession session) {
        return session.spec().layout().widgets().stream().filter(w -> w.id().equals("canvas")).findFirst().orElseThrow();
    }

    void handle(ScreenSession session, ScreenInput input, Canvas canvas) {
        switch (input) {
            case ScreenInput.Press press -> {
                canvas.down = press.down();
                if (!press.down()) { canvas.lift(); showStats(session); }
            }
            case ScreenInput.Pointer pointer -> {
                if (!canvas.down || !"canvas".equals(pointer.widgetId())) return;
                paint(session, canvas, pointer.u(), pointer.v());
            }
            case ScreenInput.Hover hover -> { if (!"canvas".equals(hover.widgetId())) canvas.lift(); }
            case ScreenInput.Click click -> {
                if (click.widgetId() == null) return;
                switch (click.widgetId()) {
                    case "canvas" -> {
                        if (!click.right()) canvas.lift();   // left: one dot
                        paint(session, canvas, click.u(), click.v());
                        if (!click.right()) canvas.lift();
                    }
                    case "clear" -> { canvas.clear(); session.setMap("canvas", canvas.pixels); }
                    case "close" -> session.close();
                    default -> {
                        for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i].equals(click.widgetId())) choose(session, canvas, i);
                    }
                }
            }
            default -> { }
        }
    }

    private void choose(ScreenSession session, Canvas canvas, int index) {
        MapColors table = MapColors.table();
        canvas.chosen = index;
        canvas.colour = table.nearest(RGB[index]);
        session.setLayout(layout(lang, session.player(), index));
        session.trackPointer("canvas", true);
        session.setMap("canvas", canvas.pixels);
    }

    private void paint(ScreenSession session, Canvas canvas, double u, double v) {
        int[] px = canvasWidget(session).mapPixel(u, v, PANEL_W, PANEL_H);
        canvas.strokeTo(px[0], px[1]);
        session.setMap("canvas", canvas.pixels);
    }

    /** Patches and pixels the client got for the canvas so far (from Core's sender). */
    private void showStats(ScreenSession session) {
        if (!(session instanceof ScreenRuntime.Session live)) return;
        ScreenMapSender sender = live.senders.get("canvas");
        if (sender == null) return;
        session.setText("info", lang.component(session.player(), "screen.demo.paint.stats",
                "patches", sender.patchesSent(), "pixels", sender.pixelsSent()));
    }
}
