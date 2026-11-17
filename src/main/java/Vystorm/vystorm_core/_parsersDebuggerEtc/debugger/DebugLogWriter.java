package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One bounded producer queue and one file owner. No Bukkit/data-handler calls in the persistence path. */
final class DebugLogWriter implements AutoCloseable {
    record Record(String level, String message, LocalDateTime time) {
        boolean urgent() { return level.equals("HIGH") || level.equals("SHUTDOWN"); }
    }
    private record Work(Record record, boolean reload, CompletableFuture<Void> receipt) {}
    interface Sink extends AutoCloseable {
        void append(Record record) throws IOException;
        void force() throws IOException;
        default void force(Record record) throws IOException { force(); }
        void close() throws IOException;
    }
    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private final int capacity;
    private final long intervalNanos;
    private final Sink sink;
    private final DebugStateStore states;
    private final Consumer<Record> output;
    private final Consumer<String> emergency;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private boolean closing;
    private boolean wake;
    private long reportedDrops;
    private long nextWarning;
    private IOException pendingFailure;
    private List<Work> inFlight = List.of();

    DebugLogWriter(Sink sink, DebugStateStore states, int capacity, Duration interval,
                   Consumer<Record> output, Consumer<String> emergency) {
        if (capacity < 1 || interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Invalid writer limits");
        this.sink = sink; this.states = states; this.capacity = capacity;
        this.intervalNanos = interval.toNanos(); this.output = output; this.emergency = emergency;
        worker = new Thread(this::run, "Vystorm-debug-writer");
        worker.setDaemon(true);
        worker.start();
    }

    boolean isWriterThread() { return Thread.currentThread() == worker; }
    long droppedMessages() { return dropped.get(); }

    boolean offer(Record record, CompletableFuture<Void> receipt) {
        return enqueue(new Work(record, false, receipt));
    }
    CompletableFuture<Void> barrier(boolean reload) {
        CompletableFuture<Void> receipt = new CompletableFuture<>();
        enqueue(new Work(null, reload, receipt));
        return receipt;
    }

    private boolean enqueue(Work work) {
        synchronized (queue) {
            if (closing || !worker.isAlive()) return reject(work, "Debugger is closed");
            if (queue.size() == capacity) {
                // Preserve confirmations and barriers. Higher importance may replace unconfirmed detail messages.
                boolean evicted = false;
                String level = work.record == null ? "HIGH" : work.record.level;
                if (!level.equals("LOW")) evicted = evict("LOW");
                if (!evicted && (work.record == null || work.record.urgent())) evicted = evict("MID");
                if (!evicted) return reject(work, "Debugger queue is full");
            }
            queue.addLast(work);
            // Flush early when the queue fills up (startup bursts), not only on the interval, so detail messages survive.
            if (work.record == null || work.record.urgent() || work.receipt != null || queue.size() * 4 >= capacity * 3) {
                wake = true;
                queue.notifyAll();
            }
            return true;
        }
    }
    private boolean evict(String level) {
        Iterator<Work> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Work old = iterator.next();
            if (old.receipt == null && old.record != null && old.record.level.equals(level)) {
                iterator.remove(); dropped.incrementAndGet(); return true;
            }
        }
        return false;
    }
    private boolean reject(Work work, String reason) {
        dropped.incrementAndGet();
        if (work.receipt != null) work.receipt.completeExceptionally(new IOException(reason));
        return false;
    }

    private void run() {
        try {
            while (true) {
                List<Work> batch;
                boolean stop;
                synchronized (queue) {
                    long deadline = System.nanoTime() + intervalNanos;
                    while (!wake && !closing) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) break;
                        TimeUnit.NANOSECONDS.timedWait(queue, remaining);
                    }
                    wake = false;
                    batch = new ArrayList<>(queue);
                    queue.clear();
                    stop = closing;
                }
                inFlight = batch;
                // State-file failure cannot stop logging; the store keeps its last valid snapshot.
                if (stop) {
                    try { states.reload(); } catch (IOException failure) { warn(failure.getMessage()); }
                } else states.maintain();
                for (Work work : batch) process(work);
                inFlight = List.of();
                try { sink.force(); }
                catch (IOException failure) { failed(failure); }
                long total = dropped.get();
                if (total != reportedDrops) {
                    if (warn("Dropped debug messages: " + total + " (queue overflow or closed writer)")) reportedDrops = total;
                }
                if (stop) break;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warn("Debug writer interrupted during shutdown");
        } catch (Throwable failure) {
            warn("Debug writer stopped: " + failure.getClass().getSimpleName());
        } finally {
            synchronized (queue) {
                closing = true;
                for (Work work : inFlight) if (work.receipt != null) work.receipt.completeExceptionally(new IOException("Debug writer stopped"));
                for (Work work : queue) reject(work, "Debug writer stopped before persisting message");
                queue.clear();
            }
            try { sink.close(); }
            catch (IOException failure) { warn("Cannot close debug log: " + failure.getMessage()); }
        }
    }

    private void process(Work work) {
        IOException earlierFailure = pendingFailure;
        boolean reportingEarlierFailure = false;
        try {
            if (work.record == null) {
                if (work.reload) states.reload();
                sink.force();
                if (pendingFailure != null) {
                    pendingFailure = null; reportingEarlierFailure = true; throw earlierFailure;
                }
            } else {
                sink.append(work.record);
                if (work.record.urgent() || work.receipt != null) sink.force(work.record);
            }
            if (work.receipt != null) work.receipt.complete(null);
        } catch (IOException | RuntimeException failure) {
            IOException io = failure instanceof IOException ex ? ex : new IOException(failure);
            if (!reportingEarlierFailure && !work.reload) failed(io);
            else if (work.reload) warn(io.getMessage());
            if (work.receipt != null) work.receipt.completeExceptionally(io);
        }
        if (work.record != null) {
            try { output.accept(work.record); }
            catch (RuntimeException failure) { warn("Debug output callback failed: " + failure.getClass().getSimpleName()); }
        }
    }
    private void failed(IOException failure) {
        pendingFailure = failure;
        warn("Debug file write failed: " + failure.getMessage());
    }
    private boolean warn(String message) {
        long now = System.nanoTime();
        if (now < nextWarning) return false;
        nextWarning = now + TimeUnit.SECONDS.toNanos(5);
        try { emergency.accept(message); } catch (RuntimeException ignored) { /* Never recurse into debug logging. */ }
        return true;
    }

    static void await(CompletableFuture<Void> receipt, Duration timeout) throws IOException, InterruptedException, TimeoutException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Timeout must be positive");
        try { receipt.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("Debug persistence failed", failure.getCause());
        }
    }

    @Override public void close() {
        synchronized (queue) { closing = true; queue.notifyAll(); }
        if (isWriterThread()) return;
        try {
            worker.join(5000);
            if (worker.isAlive()) {
                worker.interrupt();
                // The server must not hang indefinitely on a failed storage device.
                try { emergency.accept("Debug shutdown exceeded 5 seconds; remaining messages may be lost"); }
                catch (RuntimeException ignored) {}
            }
        } catch (InterruptedException interrupted) {
            worker.interrupt(); Thread.currentThread().interrupt();
        }
    }

    /** Independent quotas: routine output can never evict critical output. */
    static final class SplitSink implements Sink {
        private final FileSink routine, critical;
        SplitSink(Path directory, long fileBytes, long poolBytes) {
            routine = new FileSink(directory.resolve("low-mid"), fileBytes, poolBytes);
            critical = new FileSink(directory.resolve("high-shutdown"), fileBytes, poolBytes);
        }
        private FileSink select(Record record) { return record.urgent() ? critical : routine; }
        @Override public void append(Record record) throws IOException { select(record).append(record); }
        @Override public void force(Record record) throws IOException { select(record).force(); }
        @Override public void force() throws IOException {
            IOException failure = null;
            try { routine.force(); } catch (IOException e) { failure = e; }
            try { critical.force(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw failure;
        }
        @Override public void close() throws IOException {
            try { routine.close(); } finally { critical.close(); }
        }
    }

    static final class FileSink implements Sink {
        private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSSSSSSSS");
        private final Path directory;
        private final long maxBytes;
        private final long poolBytes;
        private final NavigableMap<Path, Long> sizes = new TreeMap<>();
        private long totalBytes;
        private FileChannel channel;
        private FileChannel leaseChannel;
        private java.nio.channels.FileLock lease;
        private Path current;
        private LocalDate day;
        private boolean dirty;
        private boolean initialized;
        private boolean directoryDirty;

        FileSink(Path directory, long maxBytes, long poolBytes) {
            if (maxBytes < 1 || poolBytes < 1) throw new IllegalArgumentException("Log limits must be positive");
            this.directory = directory; this.maxBytes = Math.min(maxBytes, poolBytes); this.poolBytes = poolBytes;
        }
        private List<Path> logs() throws IOException {
            try (var files = Files.list(directory)) {
                return files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().matches(
                        "vystorm-debug-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{9}-[a-f0-9-]+\\.log"))
                        .sorted().toList();
            }
        }
        @Override public void append(Record record) throws IOException {
            byte[] bytes = (record.time + "\t" + record.level + "\t" + escape(record.message) + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > poolBytes) throw new IOException("Record exceeds log pool quota");
            try {
                if (channel == null) {
                    createDirectories(directory);
                    acquireLease();
                }
                if (!initialized) {
                    List<Path> logs = logs();
                    if (!logs.isEmpty()) repairTail(logs.getLast());
                    sizes.clear(); totalBytes = 0;
                    for (Path path : logs) {
                        long size = Files.size(path);
                        if (size == 0) { Files.deleteIfExists(path); continue; }
                        sizes.put(path, size); totalBytes += size;
                    }
                    initialized = true;
                }
                LocalDate today = LocalDate.now();
                if (channel == null || !today.equals(day) || (channel.size() > 0 && channel.size() + bytes.length > maxBytes)) {
                    force();
                    if (channel != null) channel.close();
                    channel = null;
                    current = directory.resolve("vystorm-debug-" + NAME.format(LocalDateTime.now()) + "-" + UUID.randomUUID() + ".log");
                    channel = FileChannel.open(current, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    day = today;
                    channel.force(true);
                    forceDirectory(directory);
                    sizes.put(current, 0L);
                }
                makeRoom(bytes.length);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                totalBytes += bytes.length; sizes.merge(current, (long) bytes.length, Long::sum);
                dirty = true;
            } catch (IOException failure) {
                discardChannel(); // Never append after a potentially partial write; recover the last complete line on retry.
                throw failure;
            }
        }
        private void acquireLease() throws IOException {
            if (lease != null) return;
            leaseChannel = FileChannel.open(directory.resolve(".writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                try { lease = leaseChannel.tryLock(); }
                catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Another debugger owns this log pool", busy); }
                if (lease == null) throw new IOException("Another process owns this log pool");
            } catch (IOException failure) {
                leaseChannel.close(); leaseChannel = null; throw failure;
            }
        }
        private void makeRoom(long incoming) throws IOException {
            while (totalBytes + incoming > poolBytes) {
                var oldest = sizes.firstEntry();
                if (oldest == null || oldest.getKey().equals(current)) throw new IOException("Cannot free log quota without removing the active file");
                Files.deleteIfExists(oldest.getKey());
                directoryDirty = true;
                sizes.remove(oldest.getKey()); totalBytes -= oldest.getValue();
            }
        }
        @Override public void force() throws IOException {
            if (channel != null && dirty) {
                try {
                    channel.force(true);
                    if (directoryDirty) { forceDirectory(directory); directoryDirty = false; }
                    dirty = false;
                }
                catch (IOException failure) { discardChannel(); throw failure; }
            }
        }
        private void discardChannel() {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            channel = null; dirty = false; initialized = false;
        }
        @Override public void close() throws IOException {
            try { force(); }
            finally {
                discardChannel();
                try { if (lease != null) lease.release(); }
                finally { lease = null; if (leaseChannel != null) { leaseChannel.close(); leaseChannel = null; } }
            }
        }
        static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("\0", "\\0");
        }
        static void repairTail(Path file) throws IOException {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long position = channel.size();
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (position > 0) {
                    long start = Math.max(0, position - buffer.capacity());
                    buffer.clear(); buffer.limit((int) (position - start));
                    long cursor = start;
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer, cursor);
                        if (read < 0) throw new IOException("Unexpected end of log during recovery");
                        cursor += read;
                    }
                    for (int i = buffer.position() - 1; i >= 0; i--) {
                        if (buffer.get(i) == '\n') {
                            long end = start + i + 1;
                            if (end != channel.size()) { channel.truncate(end); channel.force(true); }
                            return;
                        }
                    }
                    position = start;
                }
                if (channel.size() != 0) { channel.truncate(0); channel.force(true); }
            }
        }
    }

    static void createDirectories(Path directory) throws IOException {
        Path cursor = directory.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        while (cursor != null && !Files.exists(cursor)) { missing.add(cursor); cursor = cursor.getParent(); }
        Files.createDirectories(directory);
        for (Path created : missing) if (created.getParent() != null) forceDirectory(created.getParent());
    }

    static void forceDirectory(Path directory) throws IOException {
        Vystorm.vystorm_core.storage.internal.DirectorySync.force(directory);
    }
}
