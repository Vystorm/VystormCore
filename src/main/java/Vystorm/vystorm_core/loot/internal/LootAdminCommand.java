package Vystorm.vystorm_core.loot.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.loot.*;
import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /vadmin loot [list [tables|categories]|show <id>|roll <id> [options]|simulate <id> <n> [options]|reload]}.
 * Read-only previews: nothing is given to anybody. Options: {@code luck= tier= min-tier= rolls= seed= pity=
 * source=mob|chest self=<table> difficulty= y=}. @since 0.44.0
 */
public final class LootAdminCommand {
    public static final List<String> SUBCOMMANDS = List.of("list", "show", "roll", "simulate", "give", "reload");
    public static final List<String> OPTIONS = List.of("luck=", "tier=", "min-tier=", "rolls=", "seed=", "pity=", "source=", "self=", "difficulty=", "y=", "level=");
    /** Upper bound for simulate. */
    public static final int MAX_SIMULATIONS = 20000;
    private LootAdminCommand() {}
    private static Lang lang() { return Localization.get().core(); }

    public static void execute(JavaPlugin core, CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        try {
            switch (sub) {
                case "list" -> list(sender, args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "");
                case "categories" -> list(sender, "categories");
                case "tables" -> list(sender, "tables");
                case "show" -> { if (args.length < 3) lang().send(sender, "loot.usage"); else show(sender, args[2]); }
                case "give" -> { if (args.length < 3) lang().send(sender, "loot.usage"); else give(sender, args[2], Arrays.copyOfRange(args, 3, args.length)); }
                case "roll" -> { if (args.length < 3) lang().send(sender, "loot.usage"); else roll(sender, args[2], Arrays.copyOfRange(args, 3, args.length)); }
                case "simulate", "sim" -> {
                    if (args.length < 4) { lang().send(sender, "loot.usage"); return; }
                    int n = Integer.parseInt(args[3]);
                    simulate(sender, args[2], Math.max(1, Math.min(MAX_SIMULATIONS, n)), Arrays.copyOfRange(args, 4, args.length));
                }
                case "reload" -> {
                    try {
                        LootTableConfig.reloadCore(core);
                        MobLoot.configure(MobLoot.Settings.of(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.File(core.getDataFolder(), "config.yml"))));
                        lang().send(sender, "loot.reloaded", "count", LootTables.ids().size()); }
                    catch (Exception failure) { lang().send(sender, "loot.reload-failed", "error", String.valueOf(failure.getMessage())); }
                }
                default -> lang().send(sender, "loot.usage");
            }
        } catch (IllegalArgumentException invalid) {
            lang().send(sender, "loot.invalid", "error", String.valueOf(invalid.getMessage()));
        }
    }

    public static List<String> complete(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 2) out.addAll(SUBCOMMANDS);
        else if (a.length == 3 && Set.of("show", "roll", "simulate", "sim", "give").contains(a[1].toLowerCase(Locale.ROOT))) out.addAll(LootTables.ids());
        else if (a.length == 3 && a[1].equalsIgnoreCase("list")) out.addAll(List.of("tables", "categories"));
        else if (a.length == 4 && a[1].toLowerCase(Locale.ROOT).startsWith("sim")) out.addAll(List.of("1000", "10000"));
        else if (a.length >= 4 && Set.of("roll", "simulate", "sim", "give").contains(a[1].toLowerCase(Locale.ROOT))) out.addAll(OPTIONS);
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).limit(100).toList();
    }

    private static void list(CommandSender sender, String filter) {
        List<String> ids = LootTables.ids().stream().filter(id -> filter.startsWith("cat") ? id.startsWith("category:")
                : filter.startsWith("tab") ? !id.startsWith("category:") : true).toList();
        lang().send(sender, "loot.list-header", "count", ids.size());
        for (String id : ids) {
            LootTable t = LootTables.find(id).orElse(null);
            if (t == null) continue;
            lang().send(sender, "loot.list-line", "id", id, "owner", LootTables.owner(id).orElse("?"), "entries", t.entries().size(),
                    "rolls", t.minRolls() == t.maxRolls() ? String.valueOf(t.minRolls()) : t.minRolls() + "-" + t.maxRolls());
        }
    }

    private static void show(CommandSender sender, String id) {
        LootTable t = LootTables.find(id).orElse(null);
        if (t == null) { lang().send(sender, "loot.unknown", "id", id); return; }
        lang().send(sender, "loot.show-header", "id", id, "rolls", t.minRolls() + "-" + t.maxRolls(), "chance", percent(t.chance()));
        double total = t.entries().stream().mapToDouble(e -> e.weight(LootTable.Source.CHEST)).sum();
        for (LootTable.Entry e : t.entries()) {
            String amount = e.minAmount() == e.maxAmount() ? String.valueOf(e.minAmount()) : e.minAmount() + "-" + e.maxAmount();
            lang().send(sender, "loot.show-line", "item", e.item().isEmpty() ? "(nothing)" : e.item(), "amount", amount,
                    "weight", e.chestWeight(), "tier", e.tier(LootTable.Source.CHEST).name().toLowerCase(Locale.ROOT),
                    "percent", percent(total <= 0 ? 0 : e.weight(LootTable.Source.CHEST) / total)
                            + (e.options().fallback() ? " fallback" : ""));
        }
    }

    private static void roll(CommandSender sender, String id, String[] options) {
        if (!LootTables.exists(id)) { lang().send(sender, "loot.unknown", "id", id); return; }
        LootResult result = configure(LootRoll.table(id), sender, options).roll();
        lang().send(sender, "loot.roll-header", "id", id, "count", result.hits().size());
        if (result.isEmpty()) lang().send(sender, "loot.roll-empty");
        for (LootResult.Hit hit : result.hits())
            lang().send(sender, "loot.roll-line", "amount", hit.amount(), "item", hit.item(), "tier", hit.tier().name().toLowerCase(Locale.ROOT)
                    + (hit.pity() ? " (pity)" : ""), "percent", percent(hit.probability()), "table", hit.table());
        for (String problem : result.problems()) lang().send(sender, "loot.problem", "text", problem);
    }

    private static void simulate(CommandSender sender, String id, int n, String[] options) {
        if (!LootTables.exists(id)) { lang().send(sender, "loot.unknown", "id", id); return; }
        Map<String, Integer> contains = new TreeMap<>();
        Map<String, Long> amounts = new TreeMap<>();
        Map<LootTier, Integer> tiers = new EnumMap<>(LootTier.class);
        int hitsTotal = 0, empty = 0;
        Set<String> problems = new LinkedHashSet<>();
        // A fixed seed= makes every iteration identical; vary it per iteration.
        Long seed = seed(options);
        for (int i = 0; i < n; i++) {
            LootRoll roll = configure(LootRoll.table(id), sender, options);
            if (seed != null) roll.seed(seed + i);
            LootResult r = roll.roll();
            problems.addAll(r.problems());
            if (r.isEmpty()) empty++;
            Set<String> seen = new HashSet<>();
            for (LootResult.Hit hit : r.hits()) {
                hitsTotal++;
                tiers.merge(hit.tier(), 1, Integer::sum);
                amounts.merge(hit.item(), (long) hit.amount(), Long::sum);
                if (seen.add(hit.item())) contains.merge(hit.item(), 1, Integer::sum);
            }
        }
        lang().send(sender, "loot.sim-header", "id", id, "n", n, "hits", String.format(Locale.ROOT, "%.2f", hitsTotal / (double) n),
                "empty", percent(empty / (double) n));
        for (LootTier tier : LootTier.values())
            lang().send(sender, "loot.sim-tier", "tier", tier.name().toLowerCase(Locale.ROOT),
                    "percent", percent(hitsTotal == 0 ? 0 : tiers.getOrDefault(tier, 0) / (double) hitsTotal));
        contains.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(40).forEach(e ->
                lang().send(sender, "loot.sim-line", "item", e.getKey(), "percent", percent(e.getValue() / (double) n),
                        "avg", String.format(Locale.ROOT, "%.2f", amounts.get(e.getKey()) / (double) n)));
        for (String problem : problems) lang().send(sender, "loot.problem", "text", problem);
    }


    /** Gives the items of one roll (admin testing); {@code [player]} defaults to the sender. Server thread. */
    private static void give(CommandSender sender, String id, String[] rest) {
        if (!LootTables.exists(id)) { lang().send(sender, "loot.unknown", "id", id); return; }
        Player target = sender instanceof Player p ? p : null;
        List<String> options = new ArrayList<>();
        for (String arg : rest) {
            if (arg.contains("=")) { options.add(arg); continue; }
            target = org.bukkit.Bukkit.getPlayerExact(arg);
            if (target == null) { lang().send(sender, "loot.give-no-player", "player", arg); return; }
        }
        if (target == null) { lang().send(sender, "loot.give-no-player", "player", "-"); return; }
        LootResult result = configure(LootRoll.table(id), target, options.toArray(String[]::new)).generate();
        int count = 0;
        for (var item : result.items()) {
            count += item.getAmount();
            for (var left : target.getInventory().addItem(item).values()) target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        lang().send(sender, "loot.give-done", "count", count, "player", target.getName(), "id", id);
        for (String problem : result.problems()) lang().send(sender, "loot.problem", "text", problem);
    }
    private static Long seed(String[] options) {
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith("seed=")) return Long.parseLong(option.substring(5));
        return null;
    }

    /** Applies {@code key=value} options (also used by tests). */
    public static LootRoll configure(LootRoll roll, CommandSender sender, String[] options) {
        return configure(roll, sender instanceof Player player ? player : null, options);
    }

    private static LootRoll configure(LootRoll roll, Player who, String[] options) {
        RollContext context = who != null ? RollContext.of(who) : RollContext.NONE;
        for (String option : options) {
            int eq = option.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("expected key=value: " + option);
            String key = option.substring(0, eq).toLowerCase(Locale.ROOT), value = option.substring(eq + 1);
            switch (key) {
                case "luck" -> roll.luck(Double.parseDouble(value));
                case "tier" -> roll.tier(tier(value));
                case "min-tier" -> roll.minTier(tier(value));
                case "rolls" -> roll.rolls(Integer.parseInt(value));
                case "seed" -> roll.seed(Long.parseLong(value));
                case "pity" -> roll.guarantee(tier(value));
                case "source" -> roll.source(LootTable.Source.valueOf(value.toUpperCase(Locale.ROOT)));
                case "self" -> context = context.withSelf(value);
                case "difficulty" -> context = context.withDifficulty(Double.parseDouble(value));
                case "y" -> context = context.withY(Integer.parseInt(value));
                case "level" -> roll.mobLevel(Double.parseDouble(value));
                default -> throw new IllegalArgumentException("unknown option " + key);
            }
        }
        return roll.context(context.withSource("admin"));
    }

    private static LootTier tier(String value) {
        return LootTier.parse(value).orElseThrow(() -> new IllegalArgumentException("unknown tier " + value));
    }

    private static String percent(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }
}
