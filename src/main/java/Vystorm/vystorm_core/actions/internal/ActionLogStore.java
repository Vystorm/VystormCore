package Vystorm.vystorm_core.actions.internal;

import Vystorm.vystorm_core.actions.ActionLogEntry;
import Vystorm.vystorm_core.actions.ActionLogInterface;
import Vystorm.vystorm_core.actions.LogQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Audit log of one plugin: monthly files, batched appends every 2 s on an own daemon thread through
 * {@code api.data().files().append}, the newest 500 entries in memory. Core-internal.
 */
public final class ActionLogStore implements ActionLogInterface {
    static final int MEMORY = 500;
    interface Appender { void append(Path file, byte[] bytes) throws IOException; }

    private final Path folder;
    private final Appender appender;
    private final Consumer<Runnable> mainThread;
    private final Logger log;
    private final ConcurrentLinkedQueue<ActionLogEntry> waiting = new ConcurrentLinkedQueue<>();
    private final Deque<ActionLogEntry> memory = new ArrayDeque<>();
    private final ScheduledExecutorService worker;
    private volatile int keepMonths = 12;
    private int failures;
    private boolean rotated, closing;
    private volatile boolean loaded;

    ActionLogStore(Path folder, Appender appender, Consumer<Runnable> mainThread, Logger log, boolean background) {
        this.folder = folder;
        this.appender = appender;
        this.mainThread = mainThread;
        this.log = log;
        this.worker = background ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread t = new Thread(task, "Vystorm audit log " + folder.getParent().getFileName());
            t.setDaemon(true);
            return t;
        }) : null;
        if (worker != null) {
            worker.execute(this::start);
            worker.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
        } else start();
    }

    /** Moves months beyond the kept ones to {@code old/}; runs at the first flush, after the owner set {@link #keepMonths}. */
    private void rotate() {
        rotated = true;
        try {
            if (!Files.isDirectory(folder)) return;
            List<String> names;
            try (Stream<Path> s = Files.list(folder)) { names = s.map(p -> p.getFileName().toString()).toList(); }
            for (String name : ActionLogFormat.outdated(names, YearMonth.now(ZoneOffset.UTC), keepMonths)) {
                Path old = folder.resolve("old");
                Files.createDirectories(old);
                Files.move(folder.resolve(name), old.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            log.warning("Old audit log months in " + folder + " could not be moved: " + failure.getMessage());
        }
    }

    /** Loads the newest entries into memory. */
    private void start() {
        try {
            List<ActionLogEntry> loaded = new ArrayList<>();
            for (Path file : months()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && loaded.size() < MEMORY; i--) {
                    ActionLogEntry e = ActionLogFormat.parse(lines.get(i));
                    if (e != null) loaded.add(e);
                }
                if (loaded.size() >= MEMORY) break;
            }
            synchronized (memory) {
                List<ActionLogEntry> fresh = new ArrayList<>(memory);
                memory.clear();
                memory.addAll(fresh);
                for (ActionLogEntry e : loaded) if (memory.size() < MEMORY) memory.addLast(e);
            }
        } catch (IOException | RuntimeException failure) {
            log.warning("Audit log in " + folder + " could not be read: " + failure.getMessage());
        }
        loaded = true;
    }

    /** Month files, newest first. */
    private List<Path> months() throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        Map<YearMonth, Path> out = new TreeMap<>(Comparator.reverseOrder());
        try (Stream<Path> s = Files.list(folder)) {
            s.forEach(p -> { YearMonth m = ActionLogFormat.month(p.getFileName().toString()); if (m != null) out.put(m, p); });
        }
        return List.copyOf(out.values());
    }

    void add(ActionLogEntry e) {
        waiting.add(e);
        synchronized (memory) {
            memory.addFirst(e);
            while (memory.size() > MEMORY) memory.removeLast();
        }
    }

    @Override public List<ActionLogEntry> recent(int limit) {
        synchronized (memory) { return memory.stream().limit(Math.max(0, limit)).toList(); }
    }

    @Override public void query(LogQuery query, Consumer<List<ActionLogEntry>> done) {
        List<ActionLogEntry> fromMemory = fromMemory(query);
        if (fromMemory != null) { done.accept(fromMemory); return; }
        Runnable read = () -> {
            List<ActionLogEntry> page = new ArrayList<>();
            try {
                flush();
                int skip = (query.page() - 1) * query.pageSize();
                outer:
                for (Path file : months()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = lines.size() - 1; i >= 0; i--) {
                        ActionLogEntry e = ActionLogFormat.parse(lines.get(i));
                        if (e == null || !query.matches(e)) continue;
                        if (skip > 0) { skip--; continue; }
                        page.add(e);
                        if (page.size() >= query.pageSize()) break outer;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                log.warning("Audit log in " + folder + " could not be read: " + failure.getMessage());
            }
            List<ActionLogEntry> result = List.copyOf(page);
            mainThread.accept(() -> done.accept(result));
        };
        if (worker != null && !worker.isShutdown()) worker.execute(read);
        else read.run();
    }

    /**
     * The page straight from memory when memory can answer it: the page is full there, or memory holds every entry
     * (fewer than {@link #MEMORY}). Lets a console or RCON sender get the answer in the same call. Null otherwise.
     */
    List<ActionLogEntry> fromMemory(LogQuery query) {
        if (!loaded) return null;
        synchronized (memory) {
            int skip = (query.page() - 1) * query.pageSize();
            List<ActionLogEntry> page = new ArrayList<>();
            for (ActionLogEntry e : memory) {
                if (!query.matches(e)) continue;
                if (skip > 0) { skip--; continue; }
                page.add(e);
                if (page.size() >= query.pageSize()) return List.copyOf(page);
            }
            return memory.size() < MEMORY ? List.copyOf(page) : null;
        }
    }

    private volatile String readNode = "";

    @Override public String readNode() { return readNode; }

    @Override public void readNode(String node) { readNode = node == null ? "" : node.trim(); }

    @Override public Path folder() { return folder; }

    @Override public int keepMonths() { return keepMonths; }

    @Override public void keepMonths(int months) { keepMonths = Math.clamp(months, 1, 120); }

    @Override public synchronized void flush() {
        if (!loaded && !closing) return;   // lines written before the start read the files would be loaded twice
        if (!rotated) rotate();
        if (waiting.isEmpty()) return;
        Map<String, StringBuilder> byMonth = new TreeMap<>();
        List<ActionLogEntry> taken = new ArrayList<>();
        for (ActionLogEntry e; (e = waiting.poll()) != null; ) {
            taken.add(e);
            byMonth.computeIfAbsent(ActionLogFormat.fileName(e.time()), k -> new StringBuilder()).append(ActionLogFormat.line(e));
        }
        for (Map.Entry<String, StringBuilder> m : byMonth.entrySet()) {
            try {
                appender.append(folder.resolve(m.getKey()), m.getValue().toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                // keep the lines of that month for the next flush; warn on the first failure and every 20th
                for (ActionLogEntry e : taken) if (ActionLogFormat.fileName(e.time()).equals(m.getKey())) waiting.add(e);
                if (failures++ % 20 == 0) log.warning("Audit log " + folder.resolve(m.getKey()) + " could not be written (" + failures
                        + " times so far; the lines are kept and written later): " + failure.getMessage());
            }
        }
    }

    /** Writes what waits and stops the worker. */
    void close() {
        closing = true;
        if (worker != null) {
            worker.shutdown();
            try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        flush();
    }

    static Instant now() { return Instant.now(); }
}
