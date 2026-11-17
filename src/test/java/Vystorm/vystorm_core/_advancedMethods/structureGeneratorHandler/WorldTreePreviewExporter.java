package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.bukkit.Material;

/** Renders the real generated voxels of every sapling world tree to PNG (isometric + low side view),
 * before/after comparisons against the frozen 1.28.1 geometry, an overview and an HTML gallery.
 * Usage: WorldTreePreviewExporter outputDir [height radius oldHeight oldRadius] */
public final class WorldTreePreviewExporter {
    static final Map<Material, Color> COLORS = new HashMap<>();
    static {
        c("#6b5232", Material.OAK_LOG, Material.OAK_WOOD); c("#d9d5c9", Material.BIRCH_LOG, Material.BIRCH_WOOD);
        c("#3f2b19", Material.SPRUCE_LOG, Material.SPRUCE_WOOD); c("#5a4b26", Material.JUNGLE_LOG, Material.JUNGLE_WOOD);
        c("#6a645b", Material.ACACIA_LOG, Material.ACACIA_WOOD); c("#3d2b17", Material.DARK_OAK_LOG, Material.DARK_OAK_WOOD);
        c("#55412c", Material.MANGROVE_LOG, Material.MANGROVE_WOOD); c("#4a3b2b", Material.MANGROVE_ROOTS);
        c("#3e2933", Material.CHERRY_LOG, Material.CHERRY_WOOD); c("#5a524b", Material.PALE_OAK_LOG, Material.PALE_OAK_WOOD);
        c("#4b7d27", Material.OAK_LEAVES); c("#6b9b3d", Material.BIRCH_LEAVES); c("#385c3a", Material.SPRUCE_LEAVES);
        c("#3e8c20", Material.JUNGLE_LEAVES); c("#5f8e27", Material.ACACIA_LEAVES); c("#3a6b1f", Material.DARK_OAK_LEAVES);
        c("#5b8d2b", Material.MANGROVE_LEAVES); c("#f0b4cf", Material.CHERRY_LEAVES); c("#a2a899", Material.PALE_OAK_LEAVES);
        c("#3b6f1c", Material.VINE); c("#8e968a", Material.PALE_HANGING_MOSS); c("#5b7c2d", Material.MOSS_CARPET);
        c("#b1b7a9", Material.PALE_MOSS_CARPET);
    }
    private static void c(String hex, Material... ms) { for (Material m : ms) COLORS.put(m, Color.decode(hex)); }

    record Model(String name, Map<StructurePos, Material> blocks, int height, int radius, long millis) {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int height = args.length > 1 ? Integer.parseInt(args[1]) : 160, radius = args.length > 2 ? Integer.parseInt(args[2]) : 44;
        int oldHeight = args.length > 3 ? Integer.parseInt(args[3]) : 128, oldRadius = args.length > 4 ? Integer.parseInt(args[4]) : 28;
        Files.createDirectories(out.resolve("vergleich"));
        List<Model> models = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        for (TreeSpecies species : TreeSpecies.values()) {
            WorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 7, height, radius); // warm-up
            long t0 = System.nanoTime();
            var full = WorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 123, height, radius);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // Views above ground show what a player sees; the cut-away adds the roots below the planting level.
            Map<StructurePos, Material> plan = new LinkedHashMap<>(), section = new LinkedHashMap<>();
            full.forEach((p, m) -> { if (p.y() >= 0) plan.put(p, m); if (p.y() < height * .14 && Math.abs(p.z() - 5) <= 6) section.put(p, m); });
            long below = full.keySet().stream().filter(p -> p.y() < 0).count();
            Model model = new Model(name(species), plan, height, radius, ms);
            models.add(model);
            Model old = new Model(name(species), OldWorldTreePlan.generate(species.sapling, new StructurePos(0, 0, 0), 123, oldHeight, oldRadius), oldHeight, oldRadius, 0);
            String key = species.name().toLowerCase(Locale.ROOT);
            ImageIO.write(single(model, 45, 30, 4.2, true), "png", out.resolve(key + "-iso.png").toFile());
            ImageIO.write(single(model, 20, 6, 4.2, false), "png", out.resolve(key + "-seite.png").toFile());
            Map<StructurePos, Material> base = new LinkedHashMap<>(); plan.forEach((p, m) -> { if (p.y() < height * .36) base.put(p, m); });
            ImageIO.write(single(new Model(model.name() + " (Stammfuß)", base, height, radius, ms), 35, 22, 7, true), "png", out.resolve(key + "-stammfuss.png").toFile());
            ImageIO.write(compare(old, model), "png", out.resolve("vergleich/" + key + "-vergleich.png").toFile());
            ImageIO.write(cutaway(new Model(model.name(), section, height, radius, ms), WorldTreePlan.depth(species.sapling, height, radius), below), "png",
                    out.resolve(key + "-wurzeln.png").toFile());
            long wood = full.values().stream().filter(m -> m.name().endsWith("_LOG") || m.name().endsWith("_WOOD") || m == Material.MANGROVE_ROOTS).count();
            System.out.printf("%-10s %,8d blocks (%,d wood, %,d underground) %5d ms  old %,d%n", species, full.size(), wood, below, ms, old.blocks().size());
            rows.append("<section><h2>").append(model.name()).append("</h2><p>").append(String.format(Locale.GERMAN, "%,d Blöcke · davon %,d Holz · %,d Wurzelblöcke unter der Erde · Planung %d ms · Höhe %d · Kronenradius %d · vorher %,d Blöcke", full.size(), wood, below, ms, height, radius, old.blocks().size()))
                .append("</p><div class=row><img src='").append(key).append("-iso.png'><img src='").append(key).append("-seite.png'></div><img class=wide src='").append(key).append("-stammfuss.png'><img class=wide src='").append(key).append("-wurzeln.png'><img class=wide src='vergleich/")
                .append(key).append("-vergleich.png'></section>");
        }
        ImageIO.write(overview(models), "png", out.resolve("vergleich/uebersicht.png").toFile());
        Files.writeString(out.resolve("index.html"), """
            <!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Vystorm Weltenbäume</title><style>body{margin:0;background:#0f1720;color:#e8eef2;font:15px system-ui,sans-serif}
            main{max-width:1500px;margin:auto;padding:24px 16px}h1{font-size:34px;margin:0 0 6px}h2{margin:28px 0 4px}p{color:#aab9c5}
            .row{display:grid;grid-template-columns:1fr 1fr;gap:12px}img{width:100%;border-radius:10px;background:#18222e}
            .wide{margin-top:12px}@media(max-width:800px){.row{grid-template-columns:1fr}}</style><main>
            <h1>Weltenbäume · CustomTrees 1.29.1</h1><p>Echte Blöcke aus dem Generator (Seed 123), schematische Farben, kein Minecraft-Screenshot.
            Die kleine Figur am Stamm ist ein Spieler (2 Blöcke) als Maßstab.</p><img src="vergleich/uebersicht.png">
            """ + rows + "</main></html>");
        System.out.println("Preview written to " + out);
    }

    static String name(TreeSpecies s) {
        return switch (s) {
            case OAK -> "Eiche"; case BIRCH -> "Birke"; case SPRUCE -> "Fichte"; case JUNGLE -> "Tropenbaum"; case ACACIA -> "Akazie";
            case DARK_OAK -> "Schwarzeiche"; case MANGROVE -> "Mangrove"; case CHERRY -> "Kirschbaum"; case PALE_OAK -> "Blasseiche";
        };
    }

    // ---------------------------------------------------------------- rendering
    /** Orthographic camera: azimuth/elevation in degrees; returns projected voxel layer. */
    static final class Camera {
        final double ct, st, cp, sp, vx, vy, vz;
        Camera(double azimuth, double elevation) {
            double t = Math.toRadians(azimuth), p = Math.toRadians(elevation);
            ct = Math.cos(t); st = Math.sin(t); cp = Math.cos(p); sp = Math.sin(p);
            vx = cp * st; vy = sp; vz = cp * ct;
        }
        double sx(double x, double y, double z) { return x * ct - z * st; }
        double sy(double x, double y, double z) { return -(-sp * st * x + cp * y - sp * ct * z); }
        double depth(double x, double y, double z) { return x * vx + y * vy + z * vz; }
    }
    record Bounds(double minX, double maxX, double minY, double maxY) {}

    static Bounds bounds(Map<StructurePos, Material> blocks, Camera cam, int cx, int cz) {
        double a = Double.MAX_VALUE, b = -a, c = a, d = -a;
        for (StructurePos p : blocks.keySet()) for (int k = 0; k < 8; k++) {
            double x = p.x() - cx + (k & 1), y = p.y() + (k >> 1 & 1), z = p.z() - cz + (k >> 2 & 1);
            double u = cam.sx(x, y, z), v = cam.sy(x, y, z);
            a = Math.min(a, u); b = Math.max(b, u); c = Math.min(c, v); d = Math.max(d, v);
        }
        return new Bounds(a, b, c, d);
    }

    /** Draws blocks with painter's algorithm; (ox,oy) is the screen position of the formation centre at ground. */
    static void draw(Graphics2D g, Map<StructurePos, Material> blocks, Camera cam, double scale, double ox, double oy, int cx, int cz, boolean player) {
        Map<StructurePos, Material> all = new HashMap<>(blocks);
        if (player) {
            // A 2-block player standing beside the trunk, facing the camera.
            int px = 0, pz = 0;
            for (int d = 6; d < 60; d++) {
                int tx = (int) Math.round(cam.vx / Math.hypot(cam.vx, cam.vz) * d), tz = (int) Math.round(cam.vz / Math.hypot(cam.vx, cam.vz) * d);
                if (!blocks.containsKey(new StructurePos(cx + tx, 0, cz + tz)) && !blocks.containsKey(new StructurePos(cx + tx, 1, cz + tz))) { px = tx; pz = tz; break; }
            }
            all.put(new StructurePos(cx + px, 0, cz + pz), Material.LAPIS_BLOCK);
            all.put(new StructurePos(cx + px, 1, cz + pz), Material.ORANGE_CONCRETE);
        }
        List<StructurePos> order = new ArrayList<>(all.keySet());
        order.removeIf(p -> all.containsKey(p.add(cam.vx > 0 ? 1 : -1, 0, 0)) && all.containsKey(p.add(0, 1, 0)) && all.containsKey(p.add(0, 0, cam.vz > 0 ? 1 : -1)));
        order.sort(Comparator.comparingDouble(p -> cam.depth(p.x() + .5, p.y() + .5, p.z() + .5)));
        int fx = cam.vx > 0 ? 1 : 0, fz = cam.vz > 0 ? 1 : 0;
        for (StructurePos p : order) {
            Material m = all.get(p);
            Color base = m == Material.LAPIS_BLOCK ? new Color(0x2f4fb5) : m == Material.ORANGE_CONCRETE ? new Color(0xe0a070) : COLORS.getOrDefault(m, Color.GRAY);
            double jitter = 1 + ((((p.x() * 73856093) ^ (p.y() * 19349663) ^ (p.z() * 83492791)) & 15) - 7.5) / 110.0;
            if (m == Material.BIRCH_LOG || m == Material.BIRCH_WOOD) if (((p.x() * 31 + p.y() * 7 + p.z() * 17) & 15) < 2) base = new Color(0x2c2b28);
            double x = p.x() - cx, y = p.y(), z = p.z() - cz;
            if (!all.containsKey(p.add(0, 1, 0))) face(g, cam, scale, ox, oy, shade(base, 1.13 * jitter), x, y + 1, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x, y + 1, z + 1);
            if (!all.containsKey(p.add(fx == 1 ? 1 : -1, 0, 0))) face(g, cam, scale, ox, oy, shade(base, .74 * jitter), x + fx, y, z, x + fx, y, z + 1, x + fx, y + 1, z + 1, x + fx, y + 1, z);
            if (!all.containsKey(p.add(0, 0, fz == 1 ? 1 : -1))) face(g, cam, scale, ox, oy, shade(base, .90 * jitter), x, y, z + fz, x + 1, y, z + fz, x + 1, y + 1, z + fz, x, y + 1, z + fz);
        }
    }
    private static void face(Graphics2D g, Camera cam, double s, double ox, double oy, Color color, double... v) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < 12; i += 3) {
            double u = ox + cam.sx(v[i], v[i + 1], v[i + 2]) * s, w = oy + cam.sy(v[i], v[i + 1], v[i + 2]) * s;
            if (i == 0) path.moveTo(u, w); else path.lineTo(u, w);
        }
        path.closePath(); g.setColor(color); g.fill(path); if (s >= 2.5) g.draw(path);
    }
    private static Color shade(Color c, double f) {
        return new Color(Math.min(255, (int) (c.getRed() * f)), Math.min(255, (int) (c.getGreen() * f)), Math.min(255, (int) (c.getBlue() * f)));
    }
    private static BufferedImage canvas(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x1b2a3a), 0, h, new Color(0x0e141c))); g.fillRect(0, 0, w, h);
        g.dispose(); return img;
    }
    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static BufferedImage single(Model model, double az, double el, double scale, boolean iso) {
        Camera cam = new Camera(az, el);
        Bounds b = bounds(model.blocks(), cam, 5, 5);
        int w = (int) ((b.maxX() - b.minX()) * scale) + 80, h = (int) ((b.maxY() - b.minY()) * scale) + 130;
        BufferedImage img = canvas(w, h);
        Graphics2D g = graphics(img);
        double ox = 40 - b.minX() * scale, oy = 90 - b.minY() * scale;
        draw(g, model.blocks(), cam, scale, ox, oy, 5, 5, true);
        label(g, model.name() + (iso ? " · isometrisch" : " · Seitenansicht"), String.format(Locale.GERMAN, "%,d Blöcke · Höhe %d · Kronenradius %d · Planung %d ms · Figur = Spieler (2 Blöcke)",
                model.blocks().size(), model.height(), model.radius(), model.millis()), 24, 40);
        g.dispose(); return img;
    }
    /** Side view of a 13-block-thick slice through the trunk centre; the soil below the planting level is drawn translucent. */
    static BufferedImage cutaway(Model model, int depth, long below) {
        Camera cam = new Camera(0, 0);
        double scale = 7;
        Bounds b = bounds(model.blocks(), cam, 5, 5);
        int w = (int) ((b.maxX() - b.minX()) * scale) + 80, h = (int) ((b.maxY() - b.minY()) * scale) + 150;
        BufferedImage img = canvas(w, h);
        Graphics2D g = graphics(img);
        double ox = 40 - b.minX() * scale, oy = 110 - b.minY() * scale;
        draw(g, model.blocks(), cam, scale, ox, oy, 5, 5, true);
        int ground = (int) Math.round(oy);
        g.setColor(new Color(110, 76, 44, 120)); g.fillRect(0, ground, w, h - ground);
        g.setColor(new Color(0x7fb04f)); g.fillRect(0, ground - 1, w, 3);
        g.setColor(new Color(0xd9c7a8)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("Erdoberfläche (Pflanzebene)", 24, ground - 8);
        g.drawString("unter der Erde: bis " + depth + " Blöcke tief", 24, ground + 22);
        label(g, model.name() + " · Schnitt durch den Stammfuß", String.format(Locale.GERMAN,
                "%,d Wurzelblöcke unter der Erde · Tiefe bis %d · Figur = Spieler (2 Blöcke)", below, depth), 24, 40);
        g.dispose(); return img;
    }
    static BufferedImage compare(Model old, Model now) {
        Camera cam = new Camera(30, 14);
        double scale = 4;
        Bounds a = bounds(old.blocks(), cam, 4, 4), b = bounds(now.blocks(), cam, 5, 5);
        double minY = Math.min(a.minY(), b.minY()), maxY = Math.max(a.maxY(), b.maxY());
        int ra = (int) ((a.maxX() - a.minX()) * scale) + 60, wa = Math.max(ra, 470), wb = Math.max(620, (int) ((b.maxX() - b.minX()) * scale) + 60);
        int h = (int) ((maxY - minY) * scale) + 150;
        BufferedImage img = canvas(wa + wb + 20, h);
        Graphics2D g = graphics(img);
        double oy = 110 - minY * scale;
        draw(g, old.blocks(), cam, scale, 30 + (wa - ra) / 2.0 - a.minX() * scale, oy, 4, 4, true);
        draw(g, now.blocks(), cam, scale, wa + 20 + 30 - b.minX() * scale, oy, 5, 5, true);
        g.setColor(new Color(0x40556a)); g.fillRect(wa + 8, 20, 3, h - 40);
        label(g, "vorher 1.28.1 · " + old.name(), String.format(Locale.GERMAN, "Höhe %d · Radius %d · %,d Blöcke", old.height(), old.radius(), old.blocks().size()), 24, 40);
        label(g, "nachher 1.29.1 · " + now.name(), String.format(Locale.GERMAN, "Höhe %d · Radius %d · %,d Blöcke · gleicher Maßstab", now.height(), now.radius(), now.blocks().size()), wa + 44, 40);
        g.dispose(); return img;
    }
    static BufferedImage overview(List<Model> models) {
        Camera cam = new Camera(30, 14);
        double scale = 2.2;
        int cols = 3, cellW = 0, cellH = 0;
        List<Bounds> all = new ArrayList<>();
        for (Model m : models) { Bounds b = bounds(m.blocks(), cam, 5, 5); all.add(b);
            cellW = Math.max(cellW, (int) ((b.maxX() - b.minX()) * scale) + 30); cellH = Math.max(cellH, (int) ((b.maxY() - b.minY()) * scale) + 70); }
        int rows = (models.size() + cols - 1) / cols;
        BufferedImage img = canvas(cols * cellW + 40, rows * cellH + 90);
        Graphics2D g = graphics(img);
        label(g, "Weltenbäume 1.29.1 · alle Arten im gleichen Maßstab", "Standard: Höhe " + models.get(0).height() + ", Kronenradius " + models.get(0).radius() + " · Figur = Spieler (2 Blöcke)", 24, 40);
        for (int i = 0; i < models.size(); i++) {
            Model m = models.get(i); Bounds b = all.get(i);
            int col = i % cols, row = i / cols;
            double ox = 20 + col * cellW + cellW / 2.0 - (b.minX() + b.maxX()) / 2 * scale, oy = 90 + (row + 1) * cellH - 40 - b.maxY() * scale;
            draw(g, m.blocks(), cam, scale, ox, oy, 5, 5, true);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15)); g.setColor(new Color(0xe8eef2));
            g.drawString(m.name() + String.format(Locale.GERMAN, "  %,d Blöcke", m.blocks().size()), 30 + col * cellW, 90 + (row + 1) * cellH - 12);
        }
        g.dispose(); return img;
    }
    private static void label(Graphics2D g, String title, String sub, int x, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24)); g.setColor(new Color(0xeef4f7)); g.drawString(title, x, y);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15)); g.setColor(new Color(0xa8bccb)); g.drawString(sub, x, y + 24);
    }
}
