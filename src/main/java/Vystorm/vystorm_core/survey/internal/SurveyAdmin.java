package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.JobOptions;
import Vystorm.vystorm_core.survey.RoomInfo;
import Vystorm.vystorm_core.survey.SurveyJob;
import Vystorm.vystorm_core.survey.SurveyRule;
import Vystorm.vystorm_core.survey.WorldSurvey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /vadmin survey status|start|pause|resume|cancel|rule|at}. Called by the admin dashboard (permission checked
 * there). Server thread; answers of {@code at} come back on the server thread too.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyAdmin {
    static final List<String> SUBCOMMANDS = List.of("status", "start", "pause", "resume", "cancel", "rule", "at");
    static final List<String> OPTIONS = List.of("generate=", "workers=", "snapshots=", "ms=", "tps=", "yield=");
    static final List<String> RULE_KEYS = List.of("reach", "hall-radius", "void-below", "doors-stop-reach");

    private SurveyAdmin() {}

    private static Lang lang() { return Localization.get().core(); }

    /** {@code args} as typed after {@code /vadmin}: {@code survey ...}. */
    public static void handle(CommandSender sender, Plugin core, String[] args) {
        Lang lang = lang();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        SurveyRuntime rt = WorldSurvey.runtime();
        if (rt == null) {
            lang.send(sender, "survey.switched-off");
            if (!sub.equals("status")) lang.send(sender, "survey.usage");
            return;
        }
        switch (sub) {
            case "status" -> status(sender, rt, args.length > 2 ? world(sender, args[2]) : null, args.length > 2);
            case "start" -> start(sender, rt, args);
            case "pause", "resume", "cancel" -> control(sender, rt, sub, args.length > 2 ? args[2] : null);
            case "rule" -> rule(sender, rt, args);
            case "at" -> at(sender, rt, core, args);
            default -> lang.send(sender, "survey.usage");
        }
    }

    private static World world(CommandSender sender, String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) lang().send(sender, "survey.unknown-world", "world", name);
        return w;
    }

    private static void status(CommandSender sender, SurveyRuntime rt, World only, boolean asked) {
        if (asked && only == null) return;
        Lang lang = lang();
        long n = rt.surveys.get();
        lang.send(sender, "survey.status", "workers", rt.poolSize, "surveys", n,
                "millis", n == 0 ? "0" : String.format(Locale.ROOT, "%.2f", rt.surveyNanos.get() / 1e6 / n),
                "bytes", n == 0 ? 0 : rt.storedBytes.get() / n, "waiting", rt.queued() + rt.inFlight.get(),
                "failures", rt.failures.get(), "listeners", rt.listenerCount());
        long ticks = Math.max(1, rt.currentTick());
        rt.core.getLogger().info("World survey work: " + rt.breakdown());
        rt.core.getLogger().info("World survey loads: " + rt.loadLine());
        lang.send(sender, "survey.status-tick", "mspt", String.format(Locale.ROOT, "%.2f", PlatformCompat.getAverageTickTime()),
                "pump", String.format(Locale.ROOT, "%.3f", rt.pumpNanos.get() / 1e6 / ticks));
        if (!WorldSurvey.requiredBy().isEmpty()) lang.send(sender, "survey.required", "plugins", String.join(", ", WorldSurvey.requiredBy()));
        for (World w : Bukkit.getWorlds()) {
            if (only != null && !only.equals(w)) continue;
            SurveyRuntime.WorldState ws = rt.state(w);
            SurveyRule r = ws.rule;
            long[] files = ws.files;
            lang.send(sender, "survey.status-world", "world", w.getName(), "tracked", ws.open.size(), "stale", ws.stale.size(),
                    "files", files[0], "size", files[1] / 1024, "reach", r.reach(), "hall", r.hallRadius(),
                    "void", flag(sender, r.voidBelow()), "doors", flag(sender, r.doorsStopReach()));
            PrecomputeJob job = rt.jobs.get(w.getUID());
            long lookups = Math.max(1, rt.lookups.get()), regionBytes = 0;
            for (RegionRecords region : ws.regions.values()) regionBytes += region.bytes();
            lang.send(sender, "survey.status-load", "offered", rt.offeredAtLoad.get(), "late", rt.offeredLate.get(),
                    "missing", rt.loadedWithoutRecord.get(), "queued", ws.missing.size(), "stale", rt.staleAtLoad.get(),
                    "lookup", String.format(Locale.ROOT, "%.4f", rt.lookupNanos.get() / 1e6 / lookups),
                    "audited", rt.audited.get(), "findings", rt.auditFindings.get(), "regions", ws.regions.size(),
                    "regionkb", regionBytes / 1024);
            if (job != null) job(sender, job);
        }
    }

    static void job(CommandSender sender, PrecomputeJob job) {
        Lang lang = lang();
        SurveyJob.Progress p = job.progress();
        lang.send(sender, "survey.job", "world", job.worldName, "state", lang.plain(sender, "survey.state." + p.state().name().toLowerCase(Locale.ROOT)),
                "done", p.done(), "total", p.total(), "percent", Math.round(p.fraction() * 100), "skipped", p.skipped(),
                "rate", String.format(Locale.ROOT, "%.1f", p.chunksPerSecond()), "eta", duration(p.etaSeconds()),
                "x", job.centerX(), "z", job.centerZ(), "radius", job.radius());
    }

    private static String flag(CommandSender sender, boolean value) { return lang().plain(sender, value ? "survey.flag-yes" : "survey.flag-no"); }

    /** 1h 05m, 4m 10s, 12s; "?" when unknown. */
    static String duration(long seconds) {
        if (seconds < 0) return "?";
        if (seconds >= 3600) return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        return seconds + "s";
    }

    /** {@code start <world> <radius> [x z] [key=value ...]}. */
    private static void start(CommandSender sender, SurveyRuntime rt, String[] args) {
        Lang lang = lang();
        if (args.length < 4) { lang.send(sender, "survey.usage"); return; }
        World w = world(sender, args[2]);
        if (w == null) return;
        int radius;
        try { radius = Integer.parseInt(args[3]); }
        catch (NumberFormatException bad) { lang.send(sender, "survey.bad-option", "option", args[3]); return; }
        Location spawn = w.getSpawnLocation();
        int x = spawn.getBlockX(), z = spawn.getBlockZ();
        int i = 4;
        if (args.length >= 6 && !args[4].contains("=")) {
            try {
                x = Integer.parseInt(args[4]);
                z = Integer.parseInt(args[5]);
            } catch (NumberFormatException bad) { lang.send(sender, "survey.bad-option", "option", args[4] + " " + args[5]); return; }
            i = 6;
        }
        JobOptions o = JobOptions.defaults();
        for (; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            int eq = a.indexOf('=');
            if (eq < 0) { lang.send(sender, "survey.bad-option", "option", args[i]); return; }
            String key = a.substring(0, eq), value = a.substring(eq + 1);
            try {
                o = switch (key) {
                    case "generate" -> o.withGenerate(bool(value));
                    case "workers" -> o.withWorkers(Integer.parseInt(value));
                    case "snapshots" -> o.withSnapshotsPerTick(Integer.parseInt(value));
                    case "ms" -> o.withMillisPerTick(Double.parseDouble(value));
                    case "tps" -> o.withTpsFloor(Double.parseDouble(value));
                    case "yield" -> o.withYieldToPlayers(bool(value));
                    default -> throw new IllegalArgumentException(args[i]);
                };
            } catch (IllegalArgumentException bad) {
                lang.send(sender, "survey.bad-option", "option", args[i]);
                return;
            }
        }
        try {
            SurveyJob job = rt.precompute().start(w, x, z, radius, o);
            lang.send(sender, "survey.started", "world", w.getName(), "total", job.progress().total(), "x", x, "z", z);
        } catch (IllegalStateException | IllegalArgumentException refused) {
            lang.send(sender, "survey.refused", "error", refused.getMessage());
        }
    }

    private static boolean bool(String value) {
        if (value.equals("true") || value.equals("on")) return true;
        if (value.equals("false") || value.equals("off")) return false;
        throw new IllegalArgumentException(value);
    }

    private static void control(CommandSender sender, SurveyRuntime rt, String what, String worldName) {
        Lang lang = lang();
        List<PrecomputeJob> targets = new ArrayList<>();
        if (worldName != null) {
            World w = world(sender, worldName);
            if (w == null) return;
            PrecomputeJob job = rt.jobs.get(w.getUID());
            if (job == null) { lang.send(sender, "survey.no-job", "world", w.getName()); return; }
            targets.add(job);
        } else targets.addAll(rt.jobs.values());
        if (targets.isEmpty()) lang.send(sender, "survey.no-job", "world", "*");
        for (PrecomputeJob job : targets) {
            switch (what) {
                case "pause" -> { job.pause(); lang.send(sender, "survey.paused", "world", job.worldName); }
                case "resume" -> { job.resume(); lang.send(sender, "survey.resumed", "world", job.worldName); }
                default -> { job.cancel(); lang.send(sender, "survey.cancelled", "world", job.worldName); }
            }
        }
    }

    /** {@code rule <world> [key value]}. */
    private static void rule(CommandSender sender, SurveyRuntime rt, String[] args) {
        Lang lang = lang();
        if (args.length < 3) { lang.send(sender, "survey.usage"); return; }
        World w = world(sender, args[2]);
        if (w == null) return;
        SurveyRule r = rt.rule(w);
        if (args.length >= 5) {
            String key = args[3].toLowerCase(Locale.ROOT), value = args[4].toLowerCase(Locale.ROOT);
            try {
                r = switch (key) {
                    case "reach" -> r.withReach(Integer.parseInt(value));
                    case "hall-radius" -> r.withHallRadius(Integer.parseInt(value));
                    case "void-below" -> r.withVoidBelow(bool(value));
                    case "doors-stop-reach" -> r.withDoorsStopReach(bool(value));
                    default -> throw new IllegalArgumentException(args[3]);
                };
            } catch (IllegalArgumentException bad) {
                lang.send(sender, "survey.bad-option", "option", args[3] + " " + args[4]);
                return;
            }
            rt.rule(w, r);
            lang.send(sender, "survey.rule-set", "world", w.getName());
        } else if (args.length == 4) {
            lang.send(sender, "survey.usage");
            return;
        }
        lang.send(sender, "survey.rule", "world", w.getName(), "reach", r.reach(), "hall", r.hallRadius(),
                "void", flag(sender, r.voidBelow()), "doors", flag(sender, r.doorsStopReach()));
    }

    /** {@code at <world> <x> <y> <z>}: kind and room of a block (surveys the chunk if needed). */
    private static void at(CommandSender sender, SurveyRuntime rt, Plugin core, String[] args) {
        Lang lang = lang();
        World w;
        int x, y, z;
        try {
            if (args.length >= 6) {
                w = world(sender, args[2]);
                if (w == null) return;
                x = Integer.parseInt(args[3]);
                y = Integer.parseInt(args[4]);
                z = Integer.parseInt(args[5]);
            } else if (sender instanceof Player p && args.length == 2) {
                w = p.getWorld();
                x = p.getLocation().getBlockX();
                y = p.getLocation().getBlockY();
                z = p.getLocation().getBlockZ();
            } else {
                lang.send(sender, "survey.usage");
                return;
            }
        } catch (NumberFormatException bad) {
            lang.send(sender, "survey.usage");
            return;
        }
        int cx = x >> 4, cz = z >> 4;
        Optional<ChunkSurvey> cached = rt.rooms().cached(w, cx, cz);
        if (cached.isEmpty()) lang.send(sender, "survey.at-pending", "cx", cx, "cz", cz);
        final World world = w;
        final int fx = x, fy = y, fz = z;
        rt.rooms().request(w, cx, cz).whenComplete((s, failure) -> core.getServer().getScheduler().runTask(core, () -> {
            if (failure != null) {
                lang.send(sender, "survey.at-failed", "cx", cx, "cz", cz, "error", failure.getMessage());
                return;
            }
            if (s.cells().isEmpty()) {
                lang.send(sender, "survey.at-failed", "cx", cx, "cz", cz, "error", "chunk not loaded");
                return;
            }
            CellKind kind = s.kindAt(fx, fy, fz);
            int local = s.roomAt(fx, fy, fz);
            Object room = "";
            if (local > 0) {
                RoomInfo info = s.room(local);
                String contact = info.mouth() ? "mouth" : info.sealed() ? "sealed" : "unknown";
                room = lang.component(sender, "survey.at-room", "room", local, "cx", cx, "cz", cz, "volume", info.volume(),
                        "contact", lang.plain(sender, "survey.contact." + contact));
            }
            lang.send(sender, "survey.at", "x", fx, "y", fy, "z", fz, "world", world.getName(),
                    "kind", lang.plain(sender, "survey.kind." + kind.name().toLowerCase(Locale.ROOT)), "room", room);
            core.getLogger().info("[survey] at " + world.getName() + " " + fx + " " + fy + " " + fz + ": " + kind
                    + (local > 0 ? " room " + local + " " + s.room(local) : "") + " stamp " + Long.toHexString(s.stamp()));
        }));
    }

    /** Tab completion after {@code /vadmin survey}. */
    public static List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) out.addAll(SUBCOMMANDS);
        else {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3)
                for (World w : Bukkit.getWorlds()) out.add(w.getName());
            if (sub.equals("start")) {
                if (args.length == 4) out.addAll(List.of("500", "1000", "2000", "5000"));
                else if (args.length >= 5) out.addAll(OPTIONS);
            } else if (sub.equals("rule")) {
                if (args.length == 4) out.addAll(RULE_KEYS);
                else if (args.length == 5) out.addAll(args[3].equalsIgnoreCase("void-below") || args[3].equalsIgnoreCase("doors-stop-reach")
                        ? List.of("true", "false") : List.of("0", "3", "8"));
            }
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
