package Vystorm.vystorm_core.packet.view;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of the packet view (also {@code /vadmin packets}). Any thread.
 *
 * @param supported  whether the view runs on this server (self-test passed)
 * @param reason     why not, or the self-test summary
 * @param viewers    viewers Core follows now
 * @param attached   connections with Core's handlers in them now
 * @param layers     the registered layers
 * @param counters   packets and bytes per kind ({@code chunk.rewritten}, {@code chunk.bytesBefore}, {@code dropped.sound},
 *                   {@code unreadable.chunk}, ...); names may grow
 * @since 0.73.0
 */
public record ViewStats(boolean supported, String reason, int viewers, int attached, List<Layer> layers, Map<String, Long> counters) {
    public ViewStats {
        layers = List.copyOf(layers);
        counters = Map.copyOf(counters);
    }

    /**
     * One layer: its id, plugin and kind; chunks it was applied to, cells it wrote, packets it made Core drop,
     * failures, chunks held for it; Netty time of its {@code apply} in microseconds (p50, p95, p99).
     */
    public record Layer(String id, String plugin, LayerSpec.Kind kind, int priority, boolean collision, long chunks, long cells,
                        long dropped, long errors, long holds, double p50Micros, double p95Micros, double p99Micros) {}

    public long counter(String name) { Long v = counters.get(name); return v == null ? 0 : v; }
}
