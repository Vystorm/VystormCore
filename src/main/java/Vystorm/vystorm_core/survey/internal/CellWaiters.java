package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.OpenChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Futures of {@code openCells().request(..)} per chunk key, completed when Core publishes the chunk's open cells
 * (survey, audit) and failed when the chunk unloads. Thread-safe.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CellWaiters {
    private final Map<Long, List<CompletableFuture<OpenChunk>>> waiting = new HashMap<>();
    private int size;

    public synchronized CompletableFuture<OpenChunk> register(long key) {
        CompletableFuture<OpenChunk> f = new CompletableFuture<>();
        waiting.computeIfAbsent(key, k -> new ArrayList<>(2)).add(f);
        size++;
        return f;
    }

    public void publish(long key, OpenChunk cells) {
        List<CompletableFuture<OpenChunk>> list;
        synchronized (this) {
            list = waiting.remove(key);
            if (list == null) return;
            size -= list.size();
        }
        for (CompletableFuture<OpenChunk> f : list) f.complete(cells);
    }

    public void fail(long key, String why) {
        List<CompletableFuture<OpenChunk>> list;
        synchronized (this) {
            list = waiting.remove(key);
            if (list == null) return;
            size -= list.size();
        }
        for (CompletableFuture<OpenChunk> f : list) f.completeExceptionally(new IllegalStateException(why));
    }

    public void failAll(String why) {
        List<List<CompletableFuture<OpenChunk>>> all;
        synchronized (this) {
            all = new ArrayList<>(waiting.values());
            waiting.clear();
            size = 0;
        }
        for (List<CompletableFuture<OpenChunk>> list : all) for (CompletableFuture<OpenChunk> f : list) f.completeExceptionally(new IllegalStateException(why));
    }

    public synchronized boolean waiting(long key) { return waiting.containsKey(key); }

    public synchronized int size() { return size; }
}
