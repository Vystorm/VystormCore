package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.packet.view.ViewStats;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;

/**
 * The packet view's lines of {@code /vadmin packets}: one summary line under the client block lines, and with
 * {@code /vadmin packets view} the self-test, every layer and the counters. Called by the admin dashboard (permission
 * checked there). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PacketViewAdmin {
    private PacketViewAdmin() {}

    private static Lang lang() { return Localization.get().core(); }

    /** The summary line (and the layer lines when {@code detail}). */
    public static void handle(CommandSender sender, boolean detail) {
        Lang lang = lang();
        PacketViewRuntime rt = PacketViewRuntime.get();
        ViewStats s = rt.stats();
        int pairs = rt.pairsInPipelines();
        if (!s.supported()) {
            if (s.layers().isEmpty() && "not checked yet".equals(s.reason())) lang.send(sender, "packets.view-unchecked");
            else lang.send(sender, "packets.view-off", "reason", s.reason());
            return;
        }
        if (s.layers().isEmpty()) lang.send(sender, "packets.view-idle", "pairs", pairs);
        else {
            ViewCounters.Samples ns = rt.counters().samples("chunk.nanosNetty");
            if (ns == null) ns = rt.counters().samples("chunk.nanosOther");
            lang.send(sender, "packets.view-status", "layers", s.layers().size(), "viewers", s.viewers(), "pairs", pairs,
                    "chunks", s.counter("chunk.rewritten"), "dropped", dropped(s.counters()), "unreadable", s.counter("unreadable"),
                    "micros", ns == null ? "-" : String.format(Locale.ROOT, "%.0f/%.0f", ns.percentile(.5) / 1e3, ns.percentile(.95) / 1e3));
            for (ViewStats.Layer l : s.layers())
                lang.send(sender, l.collision() ? "packets.view-layer-collision" : "packets.view-layer", "id", l.id(), "plugin", l.plugin(),
                        "kind", l.kind().name(), "priority", l.priority(), "chunks", l.chunks(), "cells", l.cells(), "dropped", l.dropped(),
                        "errors", l.errors(), "holds", l.holds(), "micros", String.format(Locale.ROOT, "%.0f/%.0f", l.p50Micros(), l.p95Micros()));
        }
        if (!detail) return;
        lang.send(sender, "packets.view-selftest", "result", s.reason());
        for (Map.Entry<String, Long> e : s.counters().entrySet()) lang.send(sender, "packets.view-counter", "name", e.getKey(), "value", e.getValue());
    }

    static long dropped(Map<String, Long> counters) {
        long n = 0;
        for (Map.Entry<String, Long> e : counters.entrySet()) if (e.getKey().startsWith("dropped.")) n += e.getValue();
        return n;
    }
}
