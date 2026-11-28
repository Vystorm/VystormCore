package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.view.ChunkLayer;
import Vystorm.vystorm_core.packet.view.LayerSpec;
import Vystorm.vystorm_core.packet.view.ViewLayer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/**
 * The registered layers in the order they run: every CONTENT layer by priority (lower first), then by age; then every
 * MASK layer the same way. MASK always runs after CONTENT, whatever the priority numbers, so a decoration can never
 * show a cell a mask hides. The list is an immutable array swapped as a whole (server thread writes, Netty threads read
 * a snapshot per packet).
 *
 * <p>Internal: not part of the public API.
 */
public final class LayerRegistry {
    /** The order of layers. */
    public static final Comparator<Entry> ORDER = Comparator
            .comparingInt((Entry e) -> e.spec.kind() == LayerSpec.Kind.CONTENT ? 0 : 1)
            .thenComparingInt(e -> e.spec.priority())
            .thenComparingLong(e -> e.seq);

    private static final AtomicLong SEQ = new AtomicLong();
    private volatile Entry[] ordered = new Entry[0];
    private final Consumer<Entry> onClose;

    public LayerRegistry(Consumer<Entry> onClose) { this.onClose = onClose; }

    /** One registered layer with its counters. */
    public final class Entry implements ViewLayer {
        public final LayerSpec spec;
        public final Plugin owner;
        public final ChunkLayer layer;
        final long seq = SEQ.incrementAndGet();
        private volatile long holdNanos;
        private volatile boolean closed;
        public final LongAdder chunks = new LongAdder(), cells = new LongAdder(), dropped = new LongAdder(),
                errors = new LongAdder(), holds = new LongAdder();
        public final ViewCounters.Samples applyNanos = new ViewCounters.Samples();

        Entry(LayerSpec spec, Plugin owner, ChunkLayer layer) {
            this.spec = spec;
            this.owner = owner;
            this.layer = layer;
            this.holdNanos = spec.maxHoldMillis() * 1_000_000L;
        }

        public boolean mask() { return spec.kind() == LayerSpec.Kind.MASK; }

        public long holdNanos() { return holdNanos; }

        @Override public LayerSpec spec() { return spec; }

        @Override public Plugin owner() { return owner; }

        @Override public long holdMillis() { return holdNanos / 1_000_000L; }

        @Override public void holdMillis(long millis) {
            if (millis < 0 || millis > spec.maxHoldMillis()) throw new IllegalArgumentException("hold time must be 0.." + spec.maxHoldMillis() + " ms: " + millis);
            holdNanos = millis * 1_000_000L;
        }

        @Override public boolean isClosed() { return closed; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            onClose.accept(this);
        }

        void markClosed() { closed = true; }

        @Override public String toString() { return spec.id() + " (" + (owner == null ? "?" : owner.getName()) + ")"; }
    }

    /** Adds a layer (server thread); throws when the id is taken. */
    public synchronized Entry add(LayerSpec spec, Plugin owner, ChunkLayer layer) {
        for (Entry e : ordered) if (e.spec.id().equals(spec.id())) throw new IllegalStateException("a layer with the id " + spec.id() + " is registered already (by " + (e.owner == null ? "?" : e.owner.getName()) + ")");
        Entry n = new Entry(spec, owner, layer);
        Entry[] next = Arrays.copyOf(ordered, ordered.length + 1);
        next[next.length - 1] = n;
        Arrays.sort(next, ORDER);
        ordered = next;
        return n;
    }

    /** Removes a layer (server thread); true if it was there. */
    public synchronized boolean remove(Entry entry) {
        List<Entry> next = new ArrayList<>(Arrays.asList(ordered));
        boolean was = next.remove(entry);
        entry.markClosed();
        if (was) ordered = next.toArray(new Entry[0]);
        return was;
    }

    /** Every layer in run order (a snapshot; never change it). */
    public Entry[] all() { return ordered; }

    /** The layers of a world in run order (a new array). */
    public Entry[] forWorld(UUID world) {
        Entry[] all = ordered;
        int n = 0;
        for (Entry e : all) if (world == null || e.spec.covers(world)) n++;
        if (n == all.length) return all;
        Entry[] out = new Entry[n];
        int i = 0;
        for (Entry e : all) if (world == null || e.spec.covers(world)) out[i++] = e;
        return out;
    }

    public boolean isEmpty() { return ordered.length == 0; }

    /** The layers of one plugin. */
    public List<Entry> of(Plugin owner) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ordered) if (e.owner == owner) out.add(e);
        return out;
    }
}
