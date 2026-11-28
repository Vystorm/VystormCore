package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickFaces;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickProtection;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.WorldPick;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vadmin pick world <position|block|face|container|entity|area> [reach=N] [build] [faces=sides|up|down|any]
 * [max=N] [fullheight]} and {@code /vadmin pick demo [arg]} ({@link PickReturnDemo})
 * (0.71.0): in game it starts a demo world pick (barriers fail the block filter and bedrock the check, so the red preview
 * can be seen; an area takes {@code max=N} as its largest size per axis, {@code fullheight} offers "Full height" in the
 * confirm view, and bedrock cannot be its first corner; the
 * choice is printed in chat, nothing in the world changes); from the console it prints the world pick config, Core's
 * hard limits and how many picks run. Core-internal.
 */
public final class WorldPickAdmin {
    private WorldPickAdmin() {}

    static final List<String> KINDS = List.of("position", "block", "face", "container", "entity", "area");

    public static void handle(CommandSender sender, String[] args) {
        Lang lang = PickerRuntime.lang();
        if (args.length >= 2 && args[1].equalsIgnoreCase("demo")) {
            PickReturnDemo.handle(sender, args);
            return;
        }
        PickerRuntime rt = PickerRuntime.get();
        WorldPickRuntime world = rt == null ? null : rt.world;
        if (world == null) {
            lang.send(sender, "pickers.world.switched-off");
            return;
        }
        if (!(sender instanceof Player player)) {
            status(world, sender);
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("world")) {
            lang.send(sender, "pickers.world.demo.usage");
            return;
        }
        String kindName = args[2].toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kindName)) {
            lang.send(sender, "pickers.world.demo.usage");
            return;
        }
        PickKind kind = PickKind.valueOf(kindName.toUpperCase(Locale.ROOT));
        WorldPick.Builder b = WorldPick.builder(lang.ref("pickers.world.demo.title")).subtitle(lang.ref("pickers.world.demo.subtitle")).target(kind);
        Vystorm.vystorm_core.picker.AreaRules.Builder rules = Vystorm.vystorm_core.picker.AreaRules.builder();
        for (int i = 3; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            try {
                if (a.startsWith("reach=")) b.reach(Integer.parseInt(a.substring(6)));
                else if (a.equals("build")) b.protection(PickProtection.BUILD);
                else if (a.startsWith("faces=")) b.faces(faces(a.substring(6)));
                else if (kind == PickKind.AREA && a.startsWith("max=")) { int n = Integer.parseInt(a.substring(4)); rules.maxSize(n, n, n); }
                else if (kind == PickKind.AREA && a.equals("fullheight")) rules.height(Vystorm.vystorm_core.picker.AreaHeight.ASK);
                else { lang.send(sender, "pickers.world.demo.usage"); return; }
            } catch (IllegalArgumentException bad) {
                lang.send(sender, "pickers.world.demo.usage");
                return;
            }
        }
        // barriers fail the block filter, bedrock fails the check: both show red, with different reasons
        b.blocks(block -> block.getType() != Material.BARRIER)
                .check((viewer, c) -> c.target() instanceof Picked.Block pb && pb.blockType().equals("bedrock") ? PickVerdict.deny(lang.ref("pickers.world.demo.no-bedrock"))
                        : c.target() instanceof Picked.Area pa && c.firstCorner().isEmpty() && bedrock(pa) ? PickVerdict.deny(lang.ref("pickers.world.demo.no-bedrock-corner")) : PickVerdict.ok())
                .onPick((viewer, c) -> Result.ok(lang.plain(viewer, "pickers.world.demo.picked", "target", describe(viewer, c.target()))));
        if (!world.available()) {
            lang.send(sender, "pickers.world.switched-off");
            return;
        }
        try { b.area(rules.build()); } catch (IllegalStateException bad) { lang.send(sender, "pickers.world.demo.usage"); return; }
        world.world(rt.core, player, b.build(), PickReturn.none());
    }

    /** The demo's own rule: a first corner on bedrock is refused (only a loaded chunk is read, never one loaded for it). */
    static boolean bedrock(Picked.Area a) {
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(a.world());
        if (w == null || !w.isChunkLoaded(a.min().x() >> 4, a.min().z() >> 4)) return false;
        return w.getBlockAt(a.min().x(), a.min().y(), a.min().z()).getType() == Material.BEDROCK;
    }

    static Set<BlockFace> faces(String word) {
        return switch (word) {
            case "sides" -> PickFaces.SIDES;
            case "up" -> PickFaces.UP;
            case "down" -> PickFaces.DOWN;
            case "any" -> PickFaces.ANY;
            default -> throw new IllegalArgumentException(word);
        };
    }

    /** Console: config, hard limits and the running picks. */
    static void status(WorldPickRuntime world, CommandSender sender) {
        Lang lang = PickerRuntime.lang();
        WorldPickConfig c = world.config();
        lang.send(sender, "pickers.world.demo.status", "state", lang.plain(sender, world.available() ? "pickers.world.demo.state-on" : "pickers.world.demo.state-off"),
                "default", c.defaultReach(), "max", c.maxReach(), "hard", WorldPick.MAX_REACH, "distance", c.previewDistance(),
                "hard-distance", WorldPickConfig.HARD_PREVIEW_DISTANCE, "points", c.previewPoints(), "hard-points", WorldPickConfig.HARD_PREVIEW_POINTS,
                "typed", c.typedDistance(), "timeout", WorldPick.DEFAULT_TIMEOUT.toSeconds(), "max-timeout", WorldPick.MAX_TIMEOUT.toSeconds(), "running", world.size());
        lang.send(sender, "pickers.world.demo.usage");
    }

    /** One line about a target, in the viewer's language. */
    public static String describe(CommandSender viewer, Picked target) {
        Lang lang = PickerRuntime.lang();
        return switch (target) {
            case Picked.Position p -> lang.plain(viewer, "pickers.world.describe.position", "x", String.format(Locale.ROOT, "%.1f", p.at().x()),
                    "y", String.format(Locale.ROOT, "%.1f", p.at().y()), "z", String.format(Locale.ROOT, "%.1f", p.at().z()), "facing", face(p.facing()));
            case Picked.Block b -> lang.plain(viewer, "pickers.world.describe.block", "type", b.blockType(), "x", b.block().x(), "y", b.block().y(), "z", b.block().z());
            case Picked.Face f -> lang.plain(viewer, "pickers.world.describe.face", "face", face(f.face()), "type", f.blockType(), "x", f.block().x(), "y", f.block().y(), "z", f.block().z());
            case Picked.Container c -> lang.plain(viewer, "pickers.world.describe.container", "type", c.blockType(), "x", c.block().x(), "y", c.block().y(), "z", c.block().z());
            case Picked.EntityHit e -> lang.plain(viewer, "pickers.world.describe.entity", "type", e.entityType(), "uuid", e.entity(),
                    "x", String.format(Locale.ROOT, "%.1f", e.at().x()), "y", String.format(Locale.ROOT, "%.1f", e.at().y()), "z", String.format(Locale.ROOT, "%.1f", e.at().z()), "facing", face(e.facing()));
            case Picked.Area a -> lang.plain(viewer, "pickers.world.describe.area", "from", a.min().x() + " " + a.min().y() + " " + a.min().z(),
                    "to", a.max().x() + " " + a.max().y() + " " + a.max().z(), "size", AreaLimits.size(a), "blocks", AreaLimits.count(a.volume()));
        };
    }

    private static String face(BlockFace f) { return f.name().toLowerCase(Locale.ROOT); }

    /** Tab completion after {@code /vadmin pick}. */
    public static List<String> complete(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) { out.add("world"); out.add("demo"); }
        else if (a.length == 3 && a[1].equalsIgnoreCase("world")) out.addAll(KINDS);
        else if (a.length >= 4 && a[1].equalsIgnoreCase("world")) out.addAll(a[2].equalsIgnoreCase("area") ? List.of("reach=", "build", "max=", "fullheight") : List.of("reach=", "build", "faces=sides", "faces=up", "faces=down", "faces=any"));
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.startsWith(prefix)).toList();
    }
}
