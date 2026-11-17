package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import java.util.*;
import org.bukkit.Material;

/** Yggdrasil-scale geometry for the nine sapling species: braided, fluted and hollow trunks, arching
 * buttress roots, space-colonisation crowns with pipe-model limb thickness, clumped leaf pads with crown
 * shyness, curtains, vines, moss and aerial roots. Pure and deterministic; writes into a bounded local voxel
 * grid centred on the 10x10 planting square, so nothing is clipped and no world access happens.
 * Since 0.39.1 roots dive below the planting level (down to {@link #depth(int, int)} blocks): every root
 * sinks and forks underground after its visible arch, and the trunk base gets an underground flare and taproot.
 * Above-ground geometry is unchanged from 0.39.0 (the underground pass uses its own random stream).
 * @since 0.39.0 */
final class GiantTree {
    static final byte EMPTY = 0, LOG = 1, BARK = 2, ROOT = 3, LEAF = 4, VINE = 5, HANG = 6, CARPET = 7;
    private static final int SURFACE = 0, FIN = 1, ARCH = 2;
    private static final double TAU = Math.PI * 2;

    private interface Envelope { boolean inside(double x, double y, double z); }

    final TreeSpecies species;
    final int height, radius, extent, depth;
    private final long seed;
    private final int size;
    private final byte[] grid, owner;
    private final Random random, under;
    /** Root ground contacts (x, z, angle, radius) and root ends, recorded for the underground pass. */
    private final List<double[]> touches = new ArrayList<>(), ends = new ArrayList<>();
    /** Set during the underground pass: nothing above the planting level changes. */
    private boolean belowOnly;
    private final Material[] palette = new Material[8];
    private final double hs, rs, tr;
    /** Blocks rejected by the horizontal/top bounds (wood deeper than {@link #depth} is intended and not counted). */
    int clipped;
    private double leafHoles = .08;
    private double[] spineX, spineZ, spineR;
    private int spineBottom, spineTop = -1;

    // Branch skeleton (space colonisation + pipe model).
    private double[] nx = new double[4096], ny = new double[4096], nz = new double[4096], nt = new double[4096], nr = new double[4096];
    private int[] npar = new int[4096], nlimb = new int[4096], nchild = new int[4096];
    private boolean[] nseed = new boolean[4096], nhidden = new boolean[4096];
    private int nodes;

    static int rootReach(int height, int radius) {
        return (int) Math.round(Math.max(8, Math.min(34, Math.min(radius * .7, height * .22 + 6))));
    }
    /** Maximum |dx| / |dz| of any block from the formation centre (origin + 5). */
    static int extent(int height, int radius) { return Math.max(radius + 12, rootReach(height, radius) + 8); }
    /** Blocks below the planting level that underground roots may reach (6..14). @since 0.39.1 */
    static int depth(int height, int radius) { return (int) Math.max(6, Math.min(14, Math.round(height * .06 + 4))); }

    GiantTree(TreeSpecies species, long seed, int height, int radius) {
        this.species = species; this.seed = seed; this.height = height; this.radius = radius;
        extent = extent(height, radius); depth = depth(height, radius); size = extent * 2;
        grid = new byte[size * size * (height + depth)]; owner = new byte[grid.length];
        random = new Random(seed ^ 0x5DEECE66DL * (species.ordinal() + 1));
        under = new Random(seed * 0x9E3779B97F4A7C15L ^ 0x2545F4914F6CDD1DL * (species.ordinal() + 1));
        hs = height / 160.0; rs = radius / 44.0;
        tr = Math.max(.62, Math.min(1.12, .45 + .55 * Math.min(hs, rs * 1.1)));
        palette[LOG] = species.log;
        palette[BARK] = Material.valueOf(species.log.name().replace("_LOG", "_WOOD"));
        palette[ROOT] = species == TreeSpecies.MANGROVE ? Material.MANGROVE_ROOTS : species.log;
        palette[LEAF] = species.leaves;
        palette[VINE] = Material.VINE;
        palette[HANG] = species == TreeSpecies.PALE_OAK ? Vystorm.vystorm_core.compat.ServerCompat.material("PALE_HANGING_MOSS", Material.VINE) : species.leaves;
        palette[CARPET] = species == TreeSpecies.PALE_OAK ? Vystorm.vystorm_core.compat.ServerCompat.material("PALE_MOSS_CARPET", Material.MOSS_CARPET) : Material.MOSS_CARPET;
    }

    // ------------------------------------------------------------------ species
    GiantTree build() {
        int H = height, rr = rootReach(height, radius);
        switch (species) {
            case OAK -> {
                // Major Oak: colossal short bole, low split into sinuous, almost horizontal limbs; broad dome.
                int split = (int) (H * .40);
                stem(0, 0, 0, split + 4, lean(2), lean(2), 10.0 * tr, 6.4 * tr, 5.0 * tr, 10 * hs + 4, 1.35, 5, .22, .32, 2.6 * hs, 1.1, true, true, 1);
                burls(9);
                roots(12, rr, 9 * hs + 3, 17 * hs + 4, 4.4 * tr, SURFACE);
                double leaf = 4.6 * Math.sqrt(rs);
                crown(7, 4.4 * tr, .36, .46, .55, .78, .54, .66, .30, .28, dome(.44, .64, H - 2 - leaf * .7, radius - leaf * .3, .45, .32),
                        .42 * H, H - 2 - leaf * .7, radius - leaf * .3, 1700, .06, .38, leaf, .62, .10);
                barkPass(); moss(.70, .62 * H);
                drapes(LEAF, .10, 1, 5, .55, false);
            }
            case SPRUCE -> {
                // Giant sequoia / Douglas fir: massive fluted column, buttress flare, tiered drooping whorls.
                stem(0, 0, 0, H - 2, 0, 0, 9.0 * tr, 1.0, 5.6 * tr, 12 * hs + 4, .78, 9, .07, .05, .6 * hs, 1.5, true, true, 2);
                roots(12, rr * .82, 8 * hs + 4, 15 * hs + 4, 3.4 * tr, FIN);
                conifer();
                barkPass();
            }
            case BIRCH -> {
                // Silver-birch cluster: fused base, several leaning white stems, narrow weeping crowns.
                int base = (int) (13 * hs + 6);
                stem(0, 0, 0, base, 0, 0, 7.0 * tr, 5.6 * tr, 3.2 * tr, 8 * hs + 3, 1.2, 4, .22, .2, .5, .5, true, true, 3);
                roots(9, rr * .78, 5 * hs + 3, 10 * hs + 3, 3.2 * tr, SURFACE);
                int stems = 3 + random.nextInt(2);
                double phase = random.nextDouble() * TAU;
                double[][] tops = new double[stems][];
                for (int s = 0; s < stems; s++) {
                    double a = phase + s * TAU / stems + (random.nextDouble() - .5) * .6;
                    double reachTop = radius * (.40 + .1 * random.nextDouble());
                    int top = s == 0 ? H - 3 : (int) (H * (.84 + .1 * random.nextDouble())) - 1;
                    double bx = Math.cos(a) * 2.6 * tr, bz = Math.sin(a) * 2.6 * tr;
                    double tx = Math.cos(a) * reachTop, tz = Math.sin(a) * reachTop;
                    stem(bx, bz, base - 6, top, tx, tz, 3.4 * tr, 1.0, 0, 6, 1.1, 3, .08, .15, 1.6 * hs, .5, false, false, 30 + s);
                    tops[s] = new double[]{bx, bz, tx, tz, top};
                    // The stem itself is a hidden seed chain so the crown grows from it.
                    int prev = -1;
                    for (int y = (int) (H * .40); y <= top; y += 3) {
                        double u = (y - (base - 6)) / (double) (top - base + 6), w = Math.pow(u, 1.6);
                        prev = addNode(bx + (tx - bx) * w, y, bz + (tz - bz) * w, prev, s, true);
                        nhidden[prev] = true; nt[prev] = u;
                    }
                }
                double leaf = 3.6 * Math.sqrt(rs);
                Envelope env = (x, y, z) -> {
                    for (double[] t : tops) {
                        double cx = t[2] * .75, cz = t[3] * .75, cy = H * .70, ry = H * .27, rx = radius * .42;
                        double d = (sq(x - cx) + sq(z - cz)) / sq(rx) + sq((y - cy) / ry);
                        if (d <= 1 + .4 * (noise(x * .08, y * .08, z * .08, 77) - .5)) return true;
                    }
                    return false;
                };
                colonize(env, H * .42, H - 2 - leaf * .8, radius - leaf, 1500, .22, .25);
                finishSkeleton(3.9 * tr, leaf, .78, .12, false);
                barkPass();
                drapes(LEAF, .14, 3, 9, .2, false);
            }
            case JUNGLE -> {
                // Kapok: tall straight column, enormous plank buttresses, emergent flat umbrella crown.
                int top = (int) (H * .70);
                stem(0, 0, 0, top, lean(1), lean(1), 7.6 * tr, 4.8 * tr, 6.0 * tr, 14 * hs + 4, 1.2, 6, .12, .08, .8, .7, true, true, 4);
                roots(11, rr, 14 * hs + 6, 24 * hs + 6, 3.6 * tr, FIN);
                double leaf = 4.8 * Math.sqrt(rs);
                double yb = H * .74, ym = H * .85, yt = H - 2 - leaf * .55, rad = radius - leaf;
                Envelope env = (x, y, z) -> {
                    double r = Math.sqrt(x * x + z * z) / rad;
                    double v = y > ym ? (y - ym) / (yt - ym) : (ym - y) / (ym - yb);
                    return Math.pow(r, 3) + v * v <= 1 + .35 * (noise(x * .07, y * .07, z * .07, 78) - .5);
                };
                crown(6, 3.4 * tr, .62, .70, .45, .62, .80, .87, .75, -.05, env, yb, yt, rad, 1500, .10, .22, leaf, .55, .08);
                barkPass(); moss(.66, .7 * H);
                trunkVines(.34);
                drapes(VINE, .20, 4, 20, .30, true);
            }
            case ACACIA -> {
                // Umbrella thorn: short bole forking low into zig-zag leaning stems, flat layered parasol.
                int split = (int) (H * .30);
                stem(0, 0, 0, split + 3, lean(3), lean(3), 6.6 * tr, 5.0 * tr, 3.6 * tr, 9 * hs + 3, 1.25, 4, .20, .5, 2 * hs, .9, true, true, 5);
                roots(10, rr * .9, 6 * hs + 3, 12 * hs + 3, 3.6 * tr, SURFACE);
                double leaf = 4.4 * Math.sqrt(rs);
                double yt = H - 2 - leaf * .4, rad = radius - leaf;
                double offA = random.nextDouble() * TAU;
                Envelope env = (x, y, z) -> {
                    double r = Math.sqrt(x * x + z * z) / rad, n = noise(x * .06, 0, z * .06, 79) - .5;
                    double bottom = yt - (11 + 8 * (1 - r * r)) * hs - 3;
                    if (r <= 1 + .3 * n && y <= yt - r * r * 3 * hs && y >= bottom) return true;
                    double lx = x - Math.cos(offA) * rad * .35, lz = z - Math.sin(offA) * rad * .35;
                    double r2 = Math.sqrt(lx * lx + lz * lz) / (rad * .5), ly = H * .74;
                    return r2 <= 1 + .3 * n && Math.abs(y - ly + r2 * r2 * 2) <= 5 * hs + 1;
                };
                crown(7, 3.4 * tr, .24, .32, .45, .70, .74, .86, .05, -.15, env, H * .66, yt, rad, 1900, 0, .34, leaf, .48, .10);
                barkPass();
                drapes(LEAF, .05, 1, 3, .7, false);
            }
            case DARK_OAK -> {
                // Banyan: fused multi-strand bole, wide horizontal limbs dropping aerial prop roots.
                int split = (int) (H * .38);
                stem(0, 0, 0, split + 4, lean(1.5), lean(1.5), 10.0 * tr, 6.4 * tr, 4.4 * tr, 10 * hs + 4, 1.3, 7, .22, .45, 1.6 * hs, 1.2, true, true, 6);
                burls(6);
                roots(12, rr, 8 * hs + 3, 15 * hs + 4, 4.0 * tr, SURFACE);
                double leaf = 4.8 * Math.sqrt(rs);
                crown(8, 3.9 * tr, .30, .40, .50, .70, .48, .60, .25, .12, superDome(.44, .62, H - 2 - leaf * .7, radius - leaf * .3, .4, .3),
                        .42 * H, H - 2 - leaf * .7, radius - leaf * .3, 1900, .02, .30, leaf, .60, .09);
                aerialRoots(14, false);
                barkPass(); moss(.66, .66 * H);
                trunkVines(.20);
                drapes(VINE, .14, 3, 14, .45, true);
            }
            case MANGROVE -> {
                // Mangrove: bole lifted on a lattice of arching prop roots, aerial roots from the limbs.
                int lift = (int) (H * .16) + 2, split = (int) (H * .45);
                stem(0, 0, lift, split, lean(2), lean(2), 6.6 * tr, 4.6 * tr, 1.6 * tr, 6, 1.3, 5, .15, .3, 2 * hs, .9, true, true, 7);
                roots(18, rr, lift + 2, lift + 14 * hs + 4, 3.0 * tr, ARCH);
                roots(12, rr * .6, lift - 2, lift + 6 * hs + 2, 2.4 * tr, ARCH);
                double leaf = 4.2 * Math.sqrt(rs);
                crown(7, 3.3 * tr, .38, .46, .45, .62, .60, .74, .40, .12, dome(.50, .68, H - 2 - leaf * .7, radius * .94 - leaf, .4, .3),
                        .48 * H, H - 2 - leaf * .7, radius * .94 - leaf, 1550, .04, .30, leaf, .62, .09);
                aerialRoots(9, true);
                barkPass(); moss(.68, .6 * H);
                drapes(LEAF, .08, 1, 4, .5, false);
            }
            case CHERRY -> {
                // Jindai-zakura: squat, twisted, leaning bole; long sagging limbs; weeping pink curtains.
                int split = (int) (H * .30);
                stem(0, 0, 0, split + 4, lean(6), lean(6), 9.4 * tr, 6.4 * tr, 4.4 * tr, 10 * hs + 4, 1.3, 4, .25, .75, 4 * hs, 1.3, true, true, 8);
                burls(10);
                roots(11, rr, 8 * hs + 3, 15 * hs + 4, 4.0 * tr, SURFACE);
                double leaf = 4.6 * Math.sqrt(rs);
                crown(7, 4.6 * tr, .27, .34, .62, .84, .44, .56, .15, .42, dome(.42, .58, H - 2 - leaf * .6, radius - leaf * .3, .45, .35),
                        .38 * H, H - 2 - leaf * .6, radius - leaf * .3, 1750, -.12, .40, leaf, .56, .06);
                barkPass();
                drapes(LEAF, .22, 3, 12, .3, false);
            }
            case PALE_OAK -> {
                // Pale oak: tortured, heavily twisted bole full of burls; crooked sparse limbs; hanging moss.
                int split = (int) (H * .40);
                stem(0, 0, 0, split + 4, lean(3), lean(3), 9.4 * tr, 6.0 * tr, 4.0 * tr, 10 * hs + 4, 1.3, 5, .30, 1.1, 4.5 * hs, 1.4, true, true, 9);
                burls(15);
                roots(12, rr, 9 * hs + 3, 16 * hs + 4, 3.8 * tr, SURFACE);
                double leaf = 3.9 * Math.sqrt(rs);
                crown(7, 3.6 * tr, .32, .44, .40, .66, .50, .72, .40, .22, dome(.40, .62, H - 2 - leaf * .7, radius - leaf, .7, .25),
                        .38 * H, H - 2 - leaf * .7, radius - leaf, 1250, 0, .62, leaf, .62, .18);
                barkPass(); moss(.64, .6 * H);
                drapes(HANG, .28, 2, 9, 0, false);
            }
        }
        underground();
        return this;
    }

    private double lean(double amount) { return (random.nextDouble() * 2 - 1) * amount * hs; }

    /** Ellipsoidal crown; {@code shell} hollows the inside so foliage forms an outer mantle over bare limbs. */
    private Envelope dome(double yb, double ym, double yt, double rad, double lump, double shell) {
        double b = yb * height, m = ym * height;
        return (x, y, z) -> {
            double v = y > m ? (y - m) / (yt - m) : (m - y) / (m - b);
            double q = (x * x + z * z) / (rad * rad) + v * v, n = lump * (noise(x * .07, y * .07, z * .07, 80) - .5);
            if (y < m && (x * x + z * z) / (rad * rad) < .30 + n) return false; // open underside: limbs stay visible
            return q <= 1 + n && q >= shell + n;
        };
    }
    private Envelope superDome(double yb, double ym, double yt, double rad, double lump, double shell) {
        double b = yb * height, m = ym * height;
        return (x, y, z) -> {
            double v = y > m ? (y - m) / (yt - m) : (m - y) / (m - b);
            double q = Math.pow((x * x + z * z) / (rad * rad), 1.6) + v * v, n = lump * (noise(x * .07, y * .07, z * .07, 81) - .5);
            if (y < m && (x * x + z * z) / (rad * rad) < .30 + n) return false; // open underside: limbs stay visible
            return q <= 1 + n && q >= shell + n;
        };
    }

    // ------------------------------------------------------------------ trunk
    /** Horizontal-slice stem: helically twisting lobes (braided strands), bark fissures, knobby noise, hollow core. */
    private void stem(double bx, double bz, int y0, int y1, double tx, double tz, double r0, double r1, double flare, double flareLen,
                      double taper, int lobes, double lobeAmp, double twist, double gnarl, double fissure, boolean hollow, boolean record, int salt) {
        if (record) { spineX = new double[height]; spineZ = new double[height]; spineR = new double[height]; spineBottom = y0; spineTop = Math.min(y1, height - 1); }
        double theta0 = random.nextDouble() * TAU;
        int span = Math.max(1, y1 - y0), fissures = 11 + random.nextInt(5);
        for (int y = y0; y <= y1 && y < height; y++) {
            double u = (y - y0) / (double) span, w = Math.pow(u, 1.6), g = Math.min(1, u * 3);
            double cx = bx + (tx - bx) * w + gnarl * (noise(y * .035, 0, 0, salt) - .5) * 2 * g;
            double cz = bz + (tz - bz) * w + gnarl * (noise(0, y * .035, 0, salt + 1) - .5) * 2 * g;
            double rt = r1 + (r0 - r1) * Math.pow(1 - u, taper) + flare * .6 * Math.exp(-(y - y0) / flareLen);
            if (record) { spineX[y] = cx; spineZ[y] = cz; spineR[y] = rt; }
            double theta = theta0 + twist * TAU * u + 1.2 * (noise(y * .04, 5, 0, salt) - .5);
            double amp = lobeAmp * (1 + 1.4 * Math.exp(-(y - y0) / flareLen));
            double rmax = rt * (1 + amp) + 2;
            boolean solid = !hollow || y - y0 < 5 || y1 - y < 6 || rt < 5;
            double shell = 2.4 + rt * .08;
            for (int x = (int) Math.floor(cx - rmax); x <= (int) Math.ceil(cx + rmax); x++)
                for (int z = (int) Math.floor(cz - rmax); z <= (int) Math.ceil(cz + rmax); z++) {
                    double px = x + .5 - cx, pz = z + .5 - cz, d = Math.sqrt(px * px + pz * pz);
                    if (d > rmax) continue;
                    double phi = Math.atan2(pz, px);
                    double rb = rt * (1 + amp * ridge(lobes, phi - theta) + amp * .45 * ridge(lobes + 1, phi + theta * .8 + 1.3));
                    double groove = Math.cos(fissures * (phi - theta * .5) + 3 * noise(y * .05, salt, 0, 90));
                    if (groove > .72) rb -= fissure * (groove - .72) / .28;
                    rb += 1.4 * (noise(x * .17, y * .09, z * .17, salt + 3) - .5);
                    if (d <= rb && (solid || d >= rb - shell)) put(x, y, z, LOG);
                }
        }
    }
    private void burls(int count) {
        for (int i = 0; i < count; i++) {
            int y = spineBottom + 3 + random.nextInt(Math.max(1, spineTop - spineBottom - 8));
            double a = random.nextDouble() * TAU, r = spineR[y] * .92, size = (1.4 + random.nextDouble() * 2.2) * tr;
            sphere(spineX[y] + Math.cos(a) * r, y, spineZ[y] + Math.sin(a) * r, size, LOG);
        }
    }

    // ------------------------------------------------------------------ roots
    private void roots(int count, double reach, double startLo, double startHi, double r0, int style) {
        double phase = random.nextDouble() * TAU;
        for (int j = 0; j < count; j++) {
            double a = phase + j * TAU / count + (random.nextDouble() - .5) * .55 * TAU / count;
            int y = (int) Math.round(startLo + (startHi - startLo) * random.nextDouble());
            y = Math.max(spineBottom, Math.min(spineTop - 2, y));
            double sr = spineR[y] * .72;
            root(spineX[y] + Math.cos(a) * sr, y, spineZ[y] + Math.sin(a) * sr, a, reach * (.72 + .32 * random.nextDouble()),
                    r0 * 1.2 * (.8 + .35 * random.nextDouble()), style, 0);
        }
    }
    private void root(double sx, double sy, double sz, double a, double reach, double r0, int style, int depth) {
        double cx = Math.cos(a), cz = Math.sin(a), px = -cz, pz = cx;
        double[] p0 = {sx, sy, sz}, p1, p2, p3 = {sx + cx * reach, 0, sz + cz * reach};
        switch (style) {
            case FIN -> { p1 = new double[]{sx + cx * reach * .18, sy * .62, sz + cz * reach * .18};
                          p2 = new double[]{sx + cx * reach * .55, sy * .10, sz + cz * reach * .55}; }
            case ARCH -> { p1 = new double[]{sx + cx * reach * .35, sy * 1.05, sz + cz * reach * .35};
                           p2 = new double[]{sx + cx * reach * .85, sy * .55, sz + cz * reach * .85}; }
            default -> { p1 = new double[]{sx + cx * reach * .2, sy * .7, sz + cz * reach * .2};
                         p2 = new double[]{sx + cx * reach * .50, sy * .12 + 1, sz + cz * reach * .50}; }
        }
        double wander = reach * .07 * (random.nextDouble() + .4), ph = random.nextDouble() * TAU;
        int steps = (int) (reach * 1.4) + 8;
        double lx = sx, ly = sy, lz = sz, lr = r0;
        int forkAt = depth == 0 ? (int) (steps * (.42 + .18 * random.nextDouble())) : -1;
        boolean touched = false;
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            double[] p = bezier(p0, p1, p2, p3, t);
            double off = wander * Math.sin(t * TAU * 1.3 + ph) * t;
            double x = p[0] + px * off, z = p[2] + pz * off, y = p[1];
            if (style != ARCH && t > .66) y += (1.0 + r0 * .3) * Math.sin(Math.PI * (t - .66) / .34);
            double r = .7 + (r0 - .7) * Math.pow(1 - t, .85);
            byte m = style == ARCH && (r < 1.7 || y < 3) ? ROOT : LOG;
            tube(lx, ly, lz, lr, x, y, z, r, m, 0);
            if (!touched && y < 1.5) { touched = true; touches.add(new double[]{x, z, a, r}); }
            if (style == FIN && y > .5) tube(x, y, z, Math.max(.6, r * .5), x, 0, z, Math.max(.6, r * .62), LOG, 0);
            else if (style == SURFACE && y < r * 2.5 && y > .5) tube(x, y, z, r * .75, x, 0, z, r * .85, LOG, 0);
            if (s == forkAt) {
                double side = random.nextBoolean() ? 1 : -1;
                root(x, y, z, a + side * (.35 + .3 * random.nextDouble()), reach * (1 - t) * .9 + 4, r * .7, style, depth + 1);
            }
            lx = x; ly = y; lz = z; lr = r;
        }
        ends.add(new double[]{lx, lz, a, lr});
    }

    /** Roots dig in: each ground contact sinks and forks, root tips continue underground, the trunk base flares down
     * into a taproot. Uses its own random stream so the visible tree stays identical for a seed. */
    private void underground() {
        belowOnly = true;
        for (double[] t : touches)
            dive(t[0], .5, t[1], t[2], 4 + under.nextDouble() * 6, Math.max(.9, t[3] * .8), depth * (.5 + .45 * under.nextDouble()), t[3] > 2 ? 2 : 1);
        for (double[] e : ends)
            dive(e[0], .5, e[1], e[2], 3 + under.nextDouble() * 4, .9, depth * (.3 + .4 * under.nextDouble()), 0);
        if (spineTop < 0) return;
        int base = Math.max(0, spineBottom);
        double bx = spineX[base], bz = spineZ[base], br = spineR[base];
        if (spineBottom <= 0) {
            // Root flare: steep sinker roots around the bole plus a central taproot.
            int n = 6 + under.nextInt(4);
            double phase = under.nextDouble() * TAU;
            for (int k = 0; k < n; k++) {
                double a = phase + k * TAU / n + (under.nextDouble() - .5) * .4;
                dive(bx + Math.cos(a) * br * .6, .5, bz + Math.sin(a) * br * .6, a, br * .5 + 3 + under.nextDouble() * 3,
                        Math.max(1.2, 2.6 * tr), depth * (.65 + .35 * under.nextDouble()), 1);
            }
            tube(bx, 0, bz, br * .55, bx + (under.nextDouble() - .5) * 2, -depth + 1, bz + (under.nextDouble() - .5) * 2, 1.0, ROOT, 0);
        } else {
            // Lifted bole (mangrove): a taproot below the prop-root lattice.
            tube(bx, 0, bz, Math.max(1.5, br * .35), bx, -depth + 1, bz, .9, ROOT, 0);
        }
    }
    /** One tapering underground root: drifts outward by {@code len} while sinking {@code down}, with optional forks. */
    private void dive(double x, double y, double z, double a, double len, double r, double down, int forks) {
        int steps = Math.max(4, (int) Math.ceil(Math.hypot(len, down) / 1.2));
        double lx = x, ly = y, lz = z, lr = r, ang = a;
        int forkAt = forks > 0 ? Math.max(1, steps / 2 - 1 + under.nextInt(3)) : -1;
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            ang += (under.nextDouble() - .5) * .35;
            double rr = .55 + (r - .55) * (1 - t), lim = extent - Math.ceil(rr) - 2;
            double x2 = Math.max(-lim, Math.min(lim, lx + Math.cos(ang) * len / steps));
            double z2 = Math.max(-lim, Math.min(lim, lz + Math.sin(ang) * len / steps));
            double y2 = y - down * Math.pow(t, .85);
            tube(lx, ly, lz, lr, x2, y2, z2, rr, ROOT, 0);
            if (s == forkAt) {
                double side = under.nextBoolean() ? 1 : -1;
                dive(x2, y2, z2, ang + side * (.6 + .5 * under.nextDouble()), len * (1 - t) * .9 + 1.5, rr * .8,
                        Math.max(1.5, down * (1 - t) * .9 + under.nextDouble() * 2), forks - 1);
            }
            lx = x2; ly = y2; lz = z2; lr = rr;
        }
    }

    // ------------------------------------------------------------------ crown
    private void crown(int limbs, double limbR, double startLo, double startHi, double reachLo, double reachHi, double tyLo, double tyHi,
                       double sweepUp, double droop, Envelope env, double yMin, double yMax, double rMax, int points,
                       double trop, double wobble, double leaf, double flat, double holes) {
        double phase = random.nextDouble() * TAU;
        for (int j = 0; j < limbs; j++) {
            double a = phase + j * TAU / limbs + (random.nextDouble() - .5) * .5 * TAU / limbs;
            int ys = (int) Math.round(height * (startLo + (startHi - startLo) * random.nextDouble()));
            ys = Math.max(spineBottom + 2, Math.min(spineTop - 1, ys));
            double dist = radius * (reachLo + (reachHi - reachLo) * random.nextDouble());
            double yt = height * (tyLo + (tyHi - tyLo) * random.nextDouble());
            if (j == 0) { yt = Math.max(yt, yMax - leaf * .2); dist *= .45; } // a leading limb carries the crown to full height
            double cx = Math.cos(a), cz = Math.sin(a), px = -cz, pz = cx;
            double[] p0 = {spineX[ys] + cx * spineR[ys] * .45, ys, spineZ[ys] + cz * spineR[ys] * .45};
            double j1 = (random.nextDouble() - .5) * dist * .35 * (wobble + .3), j2 = (random.nextDouble() - .5) * dist * .35 * (wobble + .3);
            double[] p1 = {p0[0] + cx * dist * .25 + px * j1, ys + (yt - ys) * sweepUp, p0[2] + cz * dist * .25 + pz * j1};
            double[] p3 = {cx * dist, yt, cz * dist};
            double[] p2 = {p3[0] - cx * dist * .25 + px * j2, yt - droop * dist, p3[2] - cz * dist * .25 + pz * j2};
            seedLimb(p0, p1, p2, p3, j);
        }
        colonize(env, yMin, yMax, rMax, points, trop, wobble);
        finishSkeleton(limbR, leaf, flat, holes, true);
    }

    private int addNode(double x, double y, double z, int parent, int limb, boolean seed) {
        if (nodes == nx.length) {
            int n = nodes * 2;
            nx = Arrays.copyOf(nx, n); ny = Arrays.copyOf(ny, n); nz = Arrays.copyOf(nz, n); nt = Arrays.copyOf(nt, n); nr = Arrays.copyOf(nr, n);
            npar = Arrays.copyOf(npar, n); nlimb = Arrays.copyOf(nlimb, n); nchild = Arrays.copyOf(nchild, n);
            nseed = Arrays.copyOf(nseed, n); nhidden = Arrays.copyOf(nhidden, n);
        }
        nx[nodes] = x; ny[nodes] = y; nz[nodes] = z; npar[nodes] = parent; nlimb[nodes] = limb; nseed[nodes] = seed;
        if (parent >= 0) nchild[parent]++;
        return nodes++;
    }
    private void seedLimb(double[] p0, double[] p1, double[] p2, double[] p3, int limb) {
        double step = 2.2, acc = 0;
        double[] last = p0;
        int prev = addNode(p0[0], p0[1], p0[2], -1, limb, true);
        List<Integer> chain = new ArrayList<>(List.of(prev));
        for (int i = 1; i <= 240; i++) {
            double[] p = bezier(p0, p1, p2, p3, i / 240.0);
            acc += Math.sqrt(sq(p[0] - last[0]) + sq(p[1] - last[1]) + sq(p[2] - last[2]));
            last = p;
            if (acc >= step || i == 240) { prev = addNode(p[0], p[1], p[2], prev, limb, true); chain.add(prev); acc = 0; }
        }
        for (int i = 0; i < chain.size(); i++) nt[chain.get(i)] = i / (double) Math.max(1, chain.size() - 1);
    }

    /** Space colonisation (Runions et al.): attraction points in the crown envelope pull new nodes out of the limbs. */
    private void colonize(Envelope env, double yMin, double yMax, double rMax, int count, double trop, double wobble) {
        count = (int) (count * Math.max(.25, rs * rs * Math.min(1.3, hs)));
        double step = 2.2, di = Math.max(12, radius * .38), dk = 4.6, di2 = di * di, dk2 = dk * dk;
        double[] ax = new double[count], ay = new double[count], az = new double[count];
        int n = 0;
        for (int tries = 0; tries < count * 60 && n < count; tries++) {
            double x = (random.nextDouble() * 2 - 1) * rMax, z = (random.nextDouble() * 2 - 1) * rMax, y = yMin + random.nextDouble() * (yMax - yMin);
            if (x * x + z * z <= rMax * rMax && env.inside(x, y, z)) { ax[n] = x; ay[n] = y; az[n] = z; n++; }
        }
        final int points = n;
        int gx = (int) Math.ceil(size / di) + 2, gy = (int) Math.ceil(height / di) + 2;
        int[] cellOf = new int[points], start = new int[gx * gy * gx + 1], items = new int[points];
        for (int p = 0; p < points; p++) { cellOf[p] = cell(ax[p], ay[p], az[p], di, gx, gy); start[cellOf[p] + 1]++; }
        for (int c = 0; c < gx * gy * gx; c++) start[c + 1] += start[c];
        int[] fill = Arrays.copyOf(start, start.length);
        for (int p = 0; p < points; p++) items[fill[cellOf[p]]++] = p;
        boolean[] alive = new boolean[points]; Arrays.fill(alive, true);
        int[] closest = new int[points]; Arrays.fill(closest, -1);
        double[] cd = new double[points]; Arrays.fill(cd, di2);
        java.util.function.IntConsumer update = node -> {
            int cx = (int) Math.floor((nx[node] + extent) / di), cy = (int) Math.floor(ny[node] / di), cz = (int) Math.floor((nz[node] + extent) / di);
            for (int x = cx - 1; x <= cx + 1; x++) for (int y = cy - 1; y <= cy + 1; y++) for (int z = cz - 1; z <= cz + 1; z++) {
                if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gx) continue;
                int c = (x * gy + y) * gx + z;
                for (int i = start[c]; i < start[c + 1]; i++) {
                    int p = items[i];
                    if (!alive[p]) continue;
                    double d2 = sq(ax[p] - nx[node]) + sq(ay[p] - ny[node]) + sq(az[p] - nz[node]);
                    if (d2 < dk2) alive[p] = false;
                    else if (d2 < cd[p]) { cd[p] = d2; closest[p] = node; }
                }
            }
        };
        for (int i = 0; i < nodes; i++) if (!nhidden[i] || i % 2 == 0) update.accept(i);
        double[] sx = new double[0], sy = new double[0], sz = new double[0];
        int maxIter = (int) (radius * 1.6 / step) + 40;
        for (int iter = 0; iter < maxIter && nodes < 60_000; iter++) {
            if (sx.length < nodes) { sx = new double[nodes * 2]; sy = new double[nodes * 2]; sz = new double[nodes * 2]; }
            List<Integer> touched = new ArrayList<>();
            for (int p = 0; p < points; p++) {
                if (!alive[p] || closest[p] < 0) continue;
                int node = closest[p]; double d = Math.sqrt(cd[p]);
                if (sx[node] == 0 && sy[node] == 0 && sz[node] == 0) touched.add(node);
                sx[node] += (ax[p] - nx[node]) / d + 1e-9; sy[node] += (ay[p] - ny[node]) / d; sz[node] += (az[p] - nz[node]) / d;
            }
            if (touched.isEmpty()) break;
            touched.sort(null);
            int first = nodes;
            for (int node : touched) {
                double len = Math.sqrt(sq(sx[node]) + sq(sy[node]) + sq(sz[node]));
                double dx = sx[node] / len, dy = sy[node] / len + trop, dz = sz[node] / len;
                dx += wobble * (hash(node, iter, 1, 50) - .5); dy += wobble * (hash(node, iter, 2, 50) - .5); dz += wobble * (hash(node, iter, 3, 50) - .5);
                sx[node] = sy[node] = sz[node] = 0;
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (l < 1e-6 || nchild[node] >= 4) continue;
                double x = nx[node] + dx / l * step, y = ny[node] + dy / l * step, z = nz[node] + dz / l * step;
                if (y < 2 || y > height - 3) continue;
                addNode(x, y, z, node, nlimb[node], false);
            }
            for (int i = first; i < nodes; i++) update.accept(i);
        }
    }
    private int cell(double x, double y, double z, double di, int gx, int gy) {
        int cx = (int) Math.floor((x + extent) / di), cy = (int) Math.floor(y / di), cz = (int) Math.floor((z + extent) / di);
        return (cx * gy + cy) * gx + cz;
    }

    /** Pipe model (da Vinci, exponent 2.4) thickness, branch voxels and clumped leaf pads with crown shyness. */
    private void finishSkeleton(double limbR, double leaf, double flat, double holes, boolean drawSeeds) {
        double e = 2.4;
        double[] acc = new double[nodes];
        for (int i = nodes - 1; i >= 0; i--) {
            nr[i] = acc[i] == 0 ? 1 : Math.pow(acc[i], 1 / e);
            if (npar[i] >= 0) acc[npar[i]] += Math.pow(nr[i], e);
        }
        double sum = 0; int roots = 0;
        for (int i = 0; i < nodes; i++) if (npar[i] < 0) { sum += nr[i]; roots++; }
        double mean = Math.max(1, sum / Math.max(1, roots));
        for (int i = 0; i < nodes; i++) {
            nr[i] = Math.min(limbR * 1.2, limbR * Math.pow(nr[i] / mean, 1.45)); // steeper than linear: slender twigs
            if (nseed[i]) nr[i] = Math.min(nr[i], limbR * (1.2 - .65 * nt[i])); // limbs taper even before they fork
            if (npar[i] >= 0) nr[i] = Math.min(nr[i], nr[npar[i]]);
        }
        for (int i = 0; i < nodes; i++) {
            int p = npar[i];
            if (p < 0 || nhidden[i] || nr[i] < .62) continue;
            tube(nx[p], ny[p], nz[p], Math.max(.72, nr[p]), nx[i], ny[i], nz[i], Math.max(.72, nr[i]), LOG, 0);
        }
        leafHoles = holes;
        for (int i = 0; i < nodes; i++) {
            if (nhidden[i] && nchild[i] > 0) continue;
            int own = nlimb[i] % 120 + 1;
            if (nchild[i] == 0) {
                double r = leaf * (.8 + .4 * hash(i, 0, 0, 60));
                cluster(nx[i], ny[i] + r * flat * .3, nz[i], r, flat, own);
            } else if (nr[i] < 1.0 && hash(i, 0, 0, 61) < .3) cluster(nx[i], ny[i] + 1, nz[i], leaf * .7, flat, own);
        }
    }

    /** Conifer tiers: whorls of sagging limbs with up-turned tips, pendulous side shoots and leader spire. */
    private void conifer() {
        int yb = (int) (height * .30), ws = Math.max(4, (int) Math.round(7 * hs)), whorl = 0, own = 1;
        double rc = radius - 3;
        leafHoles = .09;
        for (int y = yb; y < height - 7; y += ws, whorl++) {
            double rel = (y - yb) / (double) (height - 7 - yb);
            double env = rc * Math.pow(1 - rel, .92);
            if (rel < .12) env *= .78 + rel * 1.8;
            int n = 6 + random.nextInt(3);
            double base = whorl * 2.4 + random.nextDouble();
            for (int j = 0; j < n; j++) {
                double a = base + j * TAU / n + (random.nextDouble() - .5) * .5;
                double len = env * (.78 + .35 * random.nextDouble());
                int yy = Math.min(spineTop - 2, y + random.nextInt(ws));
                if (len < 2.5) cluster(spineX[yy] + Math.cos(a) * 2, yy, spineZ[yy] + Math.sin(a) * 2, 2.2, .8, own);
                else spruceBranch(yy, a, len, rel, own);
                own = own % 120 + 1;
            }
        }
        int top = spineTop;
        tube(spineX[top - 9], top - 9, spineZ[top - 9], 3.0, spineX[top], top, spineZ[top], 1.0, LEAF, own);
        put((int) Math.floor(spineX[top]), Math.min(height - 1, top + 1), (int) Math.floor(spineZ[top]), LEAF);
    }
    private void spruceBranch(int y, double a, double len, double rel, int own) {
        double dx = Math.cos(a), dz = Math.sin(a), r = spineR[y];
        double droop = len * (.34 - .2 * rel), lift = len * .17, rb0 = .9 + len / 13;
        double lx = spineX[y] + dx * r * .5, ly = y, lz = spineZ[y] + dz * r * .5, lr = rb0;
        int steps = (int) (len / 1.6) + 2;
        double wob = random.nextDouble() * TAU;
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            double d = r * .5 + (len + r * .5) * t, side = Math.sin(t * 3 + wob) * len * .05;
            double x = spineX[y] + dx * d - dz * side, z = spineZ[y] + dz * d + dx * side;
            double yy = y - droop * Math.pow(t, 1.3) + lift * Math.pow(t, 4);
            double rr = Math.max(.72, rb0 * (1 - t * .75));
            tube(lx, ly, lz, lr, x, yy, z, rr, LOG, 0);
            if (t > .12) tube(lx, ly, lz, 1.6 + .8 * (1 - t), x, yy, z, 1.5 + .8 * (1 - t), LEAF, own);
            if (s % 2 == 0 && t > .15 && t < .96) for (int sgn = -1; sgn <= 1; sgn += 2) {
                double la = a + sgn * (.95 + .25 * hash(s, sgn, own, 70));
                double ll = 1.5 + len * (1 - t) * .42;
                double ex = x + Math.cos(la) * ll, ez = z + Math.sin(la) * ll, ey = yy - ll * .45;
                if (ll > 5) tube(x, yy, z, .72, x + Math.cos(la) * ll * .6, yy - ll * .25, z + Math.sin(la) * ll * .6, .72, LOG, 0);
                tube(x, yy, z, 1.4, ex, ey, ez, 1.1, LEAF, own);
                int hang = (int) (hash(s, sgn, own, 71) * 6);
                for (int k = 1; k <= hang; k++) putLeaf((int) Math.floor(ex), (int) Math.floor(ey) - 1 - k, (int) Math.floor(ez), own);
            }
            lx = x; ly = yy; lz = z; lr = rr;
        }
        cluster(lx, ly + .5, lz, 2.6, .8, own);
    }

    // ------------------------------------------------------------------ details
    private void aerialRoots(int count, boolean mangrove) {
        double minH = height * .30, maxH = height * .72, inner = spineR[Math.max(spineBottom, 0)] + 7;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            double d = Math.sqrt(nx[i] * nx[i] + nz[i] * nz[i]);
            if (!nhidden[i] && nr[i] >= .9 && nr[i] <= 3.2 && ny[i] >= minH && ny[i] <= maxH && d >= inner && d <= radius * .85) candidates.add(i);
        }
        Collections.shuffle(candidates, random);
        List<double[]> chosen = new ArrayList<>();
        for (int i : candidates) {
            if (chosen.size() >= count) break;
            boolean near = false;
            for (double[] c : chosen) if (sq(c[0] - nx[i]) + sq(c[1] - nz[i]) < 64) { near = true; break; }
            if (near) continue;
            chosen.add(new double[]{nx[i], nz[i]});
            boolean ground = random.nextDouble() < .72;
            double r = ground ? .75 + random.nextDouble() * 1.4 : .72, end = ground ? 0 : ny[i] * (.35 + .4 * random.nextDouble());
            double x = nx[i], z = nz[i], y = ny[i];
            while (y > end) {
                double ny2 = Math.max(end, y - 4);
                double x2 = x + (noise(x * .3, ny2 * .1, z * .3, 91) - .5) * 1.2, z2 = z + (noise(z * .3, ny2 * .1, x * .3, 92) - .5) * 1.2;
                tube(x, y, z, r, x2, ny2, z2, r, mangrove && (ny2 < 4 || r < 1.2) ? ROOT : LOG, 0);
                x = x2; z = z2; y = ny2;
            }
            if (ground && r > 1.2) sphere(x, .5, z, r * 1.9, mangrove ? ROOT : LOG);
        }
    }
    /** Logs showing an end-grain face become all-bark wood blocks, so branches never show tree rings. */
    private void barkPass() {
        for (int y = 0; y < height; y++) for (int x = -extent; x < extent; x++) for (int z = -extent; z < extent; z++) {
            int i = index(x, y, z);
            if (grid[i] != LOG) continue;
            if (!wood(at(x, y + 1, z)) || y > 0 && !wood(at(x, y - 1, z))) grid[i] = BARK;
        }
    }
    private void moss(double threshold, double maxY) {
        for (int y = 6; y < Math.min(height - 1, maxY); y++) for (int x = -extent; x < extent; x++) for (int z = -extent; z < extent; z++) {
            if (!wood(at(x, y, z)) || at(x, y + 1, z) != EMPTY) continue;
            if (noise(x * .11, y * .11, z * .11, 41) > threshold) put(x, y + 1, z, CARPET);
        }
    }
    private void trunkVines(double density) {
        for (int y = Math.max(1, spineBottom); y <= spineTop; y++) {
            double r = spineR[y] * 1.6 + 3;
            for (int x = (int) Math.floor(spineX[y] - r); x <= (int) Math.ceil(spineX[y] + r); x++)
                for (int z = (int) Math.floor(spineZ[y] - r); z <= (int) Math.ceil(spineZ[y] + r); z++) {
                    if (!inBounds(x, y, z) || at(x, y, z) != EMPTY) continue;
                    if (!(wood(at(x + 1, y, z)) || wood(at(x - 1, y, z)) || wood(at(x, y, z + 1)) || wood(at(x, y, z - 1)))) continue;
                    if (noise(x * .16, y * .045, z * .16, 31) > 1 - density) put(x, y, z, VINE);
                }
        }
    }
    /** Curtains hanging from the underside of the crown: leaves, moss or side-attached vines. */
    private void drapes(byte material, double prob, int minLen, int maxLen, double edgeFrom, boolean beside) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        double scale = Math.max(.4, hs);
        for (int x = -extent; x < extent; x++) for (int z = -extent; z < extent; z++) {
            double dist = Math.sqrt(sq(x + .5) + sq(z + .5)) / radius;
            if (dist < edgeFrom) continue;
            int low = -1;
            for (int y = 1; y < height; y++) if (at(x, y, z) == LEAF) { low = y; break; }
            if (low < 3) continue;
            double clump = noise(x * .14, 0, z * .14, 33);
            if (hash(x, low, z, 34) >= prob * (.4 + 1.4 * clump)) continue;
            int len = (int) Math.round((minLen + hash(x, low, z, 35) * (maxLen - minLen)) * scale * (.6 + .8 * clump));
            int cx = x, cz = z, y0 = low - 1;
            if (beside) {
                int[] d = dirs[(int) (hash(x, low, z, 36) * 4)];
                cx = x + d[0]; cz = z + d[1]; y0 = low;
                if (!inBounds(cx, y0, cz) || at(cx, y0, cz) != EMPTY) continue;
            }
            for (int n = 0; n < len; n++) {
                int y = y0 - n;
                if (y < 2 || at(cx, y, cz) != EMPTY) break;
                put(cx, y, cz, material);
            }
        }
    }

    // ------------------------------------------------------------------ voxels
    private void sphere(double cx, double cy, double cz, double r, byte m) { tube(cx, cy, cz, r, cx, cy, cz, r, m, 0); }
    private void tube(double ax, double ay, double az, double ar, double bx, double by, double bz, double br, byte m, int own) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, len2 = dx * dx + dy * dy + dz * dz, mr = Math.max(ar, br);
        int x0 = (int) Math.floor(Math.min(ax, bx) - mr), x1 = (int) Math.floor(Math.max(ax, bx) + mr);
        int y0 = Math.max(wood(m) ? -depth : 0, (int) Math.floor(Math.min(ay, by) - mr)), y1 = (int) Math.floor(Math.max(ay, by) + mr);
        int z0 = (int) Math.floor(Math.min(az, bz) - mr), z1 = (int) Math.floor(Math.max(az, bz) + mr);
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            double px = x + .5 - ax, py = y + .5 - ay, pz = z + .5 - az;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy + pz * dz) / len2));
            double r = ar + (br - ar) * t;
            if (sq(px - dx * t) + sq(py - dy * t) + sq(pz - dz * t) > r * r) continue;
            if (m == LEAF) { if (hash(x, y, z, 7) >= leafHoles) putLeaf(x, y, z, own); }
            else put(x, y, z, m);
        }
    }
    /** Noise-perturbed, flat-bottomed leaf pad. */
    private void cluster(double cx, double cy, double cz, double r, double flat, int own) {
        double ry = r * flat;
        cy = Math.min(cy, height - 1 - ry * .95); // crowns end at the configured height instead of being cut flat
        for (int x = (int) Math.floor(cx - r * 1.3); x <= (int) Math.ceil(cx + r * 1.3); x++)
            for (int y = Math.max(0, (int) Math.floor(cy - ry * 1.3)); y <= (int) Math.ceil(cy + ry * 1.3); y++)
                for (int z = (int) Math.floor(cz - r * 1.3); z <= (int) Math.ceil(cz + r * 1.3); z++) {
                    double dx = (x + .5 - cx) / r, dy = (y + .5 - cy) / ry, dz = (z + .5 - cz) / r;
                    if (dy < 0) dy *= 1.35;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > 1.7) continue;
                    if (Math.sqrt(d2) > .72 + .55 * noise(x * .23, y * .23, z * .23, 11)) continue;
                    if (hash(x, y, z, 13) < leafHoles) continue;
                    putLeaf(x, y, z, own);
                }
    }
    private boolean inBounds(int x, int y, int z) { return x >= -extent && x < extent && z >= -extent && z < extent && y >= -depth && y < height; }
    private int index(int x, int y, int z) { return ((y + depth) * size + x + extent) * size + z + extent; }
    private byte at(int x, int y, int z) { return inBounds(x, y, z) ? grid[index(x, y, z)] : EMPTY; }
    private static boolean wood(byte b) { return b == LOG || b == BARK || b == ROOT; }
    private void put(int x, int y, int z, byte m) {
        // Only wood goes underground (down to depth); foliage, vines and moss stay above the planting level.
        if (y < (wood(m) ? -depth : 0) || belowOnly && y >= 0) return;
        if (!inBounds(x, y, z)) { clipped++; return; }
        int i = index(x, y, z);
        byte c = grid[i];
        if (wood(m) ? !wood(c) : c == EMPTY) grid[i] = m;
    }
    private void putLeaf(int x, int y, int z, int own) {
        if (y < 0) return;
        if (!inBounds(x, y, z)) { clipped++; return; }
        int i = index(x, y, z);
        if (grid[i] != EMPTY) return;
        // Crown shyness: leaves of different limbs keep a one-block gap.
        if (own != 0) for (int[] d : NEIGHBOURS) {
            int ax = x + d[0], ay = y + d[1], az = z + d[2];
            if (!inBounds(ax, ay, az)) continue;
            int j = index(ax, ay, az);
            if (grid[j] == LEAF && owner[j] != 0 && owner[j] != own) return;
        }
        grid[i] = LEAF; owner[i] = (byte) own;
    }
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    int count() { int n = 0; for (byte b : grid) if (b != EMPTY) n++; return n; }

    Map<StructurePos, Material> toMap(StructurePos origin, int max) {
        int n = count();
        if (n > max) throw new IllegalArgumentException("World tree exceeds " + max + " blocks");
        Map<StructurePos, Material> result = new LinkedHashMap<>(n * 4 / 3 + 16);
        // Bottom-up order: staged placement grows the tree from its roots.
        for (int y = -depth; y < height; y++) for (int x = -extent; x < extent; x++) for (int z = -extent; z < extent; z++) {
            byte b = grid[index(x, y, z)];
            if (b != EMPTY) result.put(origin.add(5 + x, y, 5 + z), palette[b]);
        }
        return result;
    }

    // ------------------------------------------------------------------ math
    private static double sq(double v) { return v * v; }
    /** Narrow ridges with broad valleys in -1..1: fused strands rather than a sine-smooth column. */
    private static double ridge(int lobes, double angle) { return 2 * Math.pow((1 + Math.cos(lobes * angle)) / 2, 2.2) - 1; }
    private static double[] bezier(double[] a, double[] b, double[] c, double[] d, double t) {
        double u = 1 - t, w0 = u * u * u, w1 = 3 * u * u * t, w2 = 3 * u * t * t, w3 = t * t * t;
        return new double[]{a[0] * w0 + b[0] * w1 + c[0] * w2 + d[0] * w3, a[1] * w0 + b[1] * w1 + c[1] * w2 + d[1] * w3, a[2] * w0 + b[2] * w1 + c[2] * w2 + d[2] * w3};
    }
    private static long mix(long h) {
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33; return h;
    }
    private double hash(int x, int y, int z, int salt) {
        return (mix(seed + x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L + salt * 0x27D4EB2F165667C5L) >>> 11) * 0x1.0p-53;
    }
    private double noise(double x, double y, double z, int salt) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double c00 = lerp(hash(xi, yi, zi, salt), hash(xi + 1, yi, zi, salt), fx), c10 = lerp(hash(xi, yi + 1, zi, salt), hash(xi + 1, yi + 1, zi, salt), fx);
        double c01 = lerp(hash(xi, yi, zi + 1, salt), hash(xi + 1, yi, zi + 1, salt), fx), c11 = lerp(hash(xi, yi + 1, zi + 1, salt), hash(xi + 1, yi + 1, zi + 1, salt), fx);
        return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
