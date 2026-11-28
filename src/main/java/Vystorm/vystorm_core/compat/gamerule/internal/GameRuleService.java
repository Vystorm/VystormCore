package Vystorm.vystorm_core.compat.gamerule.internal;

import Vystorm.vystorm_core.compat.gamerule.GameRuleCatalog;
import Vystorm.vystorm_core.compat.gamerule.GameRuleCategory;
import Vystorm.vystorm_core.compat.gamerule.GameRuleInfo;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** Holder of the server's {@link GameRuleAccess} and the text helpers behind {@code api.compat().gameRules()}. Core-internal. */
public final class GameRuleService {
    private static volatile GameRuleAccess access;

    private GameRuleService() {}

    public static GameRuleAccess access() {
        GameRuleAccess a = access;
        if (a == null) access = a = GameRuleAccess.server();
        return a;
    }

    /** Tests: use this access instead of the server's ({@code null} = probe the server again). */
    public static void use(GameRuleAccess replacement) { access = replacement; }

    /** {@code minecraft:overworld}, {@code minecraft:the_nether}, {@code minecraft:the_end} for the main worlds, else the world's key. */
    public static String dimension(World world) {
        if (world == null) return "minecraft:overworld";
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                String main = worlds.getFirst().getName();
                if (world.getName().equals(main)) return "minecraft:overworld";
                if (world.getName().equals(main + "_nether")) return "minecraft:the_nether";
                if (world.getName().equals(main + "_the_end")) return "minecraft:the_end";
            }
        } catch (RuntimeException noServer) { /* tests */ }
        try {
            Object key = World.class.getMethod("getKey").invoke(world);
            if (key != null) return key.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError noKey) { /* Spigot without World#getKey */ }
        return "minecraft:" + world.getName().toLowerCase(Locale.ROOT);
    }

    /** The vanilla command that sets the rule in that world, with the server's own id and value. */
    public static String command(String dimension, GameRuleInfo info, Object stableValue) {
        Object raw = stableValue;
        if (info.inverted() && stableValue instanceof Boolean b) raw = !b;
        return "/execute in " + dimension + " run gamerule " + info.vanillaId() + " " + raw;
    }

    public static String label(CommandSender viewer, String id) {
        var lang = lang();
        String stable = GameRuleCatalog.stableId(id);
        String key = "game-rules.rule." + stable + ".name";
        return lang != null && lang.has(key) ? lang.plain(viewer, key) : stable;
    }

    public static String description(CommandSender viewer, String id) {
        var lang = lang();
        String stable = GameRuleCatalog.stableId(id);
        String key = "game-rules.rule." + stable + ".desc";
        return lang != null && lang.has(key) ? lang.plain(viewer, key) : lang == null ? "" : lang.plain(viewer, "game-rules.unknown-desc", "id", stable);
    }

    public static String categoryLabel(CommandSender viewer, GameRuleCategory category) {
        var lang = lang();
        String key = "game-rules.category." + category.key();
        return lang != null && lang.has(key) ? lang.plain(viewer, key) : category.key();
    }

    public static Optional<GameRuleInfo> rule(World world, String id) { return access().rule(world, id); }

    private static Vystorm.vystorm_core.lang.Lang lang() {
        try { return Vystorm.vystorm_core.lang.Localization.get().core(); } catch (RuntimeException | LinkageError none) { return null; }
    }
}
