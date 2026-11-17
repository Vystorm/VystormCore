package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DebugLogWriterTest {
    @TempDir Path directory;
    private static final Duration WAIT = Duration.ofSeconds(5);
    private DebugLogWriter.Record record(String level, String text) { return new DebugLogWriter.Record(level, text, LocalDateTime.now()); }
    static class BlockingSink implements DebugLogWriter.Sink {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<String> written = new CopyOnWriteArrayList<>();
        final AtomicInteger forces = new AtomicInteger();
        public void append(DebugLogWriter.Record record) throws IOException {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test sink timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            written.add(record.message());
        }
        public void force() { forces.incrementAndGet(); }
        public void close() {}
    }
    @Test void givenBlockedDiskWhenQueueFillsThenPreferHighWithoutBlockingNormalProducer() throws Exception {
        var sink = new BlockingSink(); var warnings = new CopyOnWriteArrayList<String>();
        var store = new DebugStateStore(directory, warnings::add);
        try (var writer = new DebugLogWriter(sink, store, 2, Duration.ofMillis(10), record -> {}, warnings::add)) {
            writer.offer(record("HIGH", "in flight"), null);
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
            writer.offer(record("LOW", "discard low"), null); writer.offer(record("MID", "keep mid"), null);
            CompletableFuture<Void> receipt = new CompletableFuture<>();
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertTrue(writer.offer(record("HIGH", "keep high"), receipt)));
            assertEquals(1, writer.droppedMessages());
            assertThrows(TimeoutException.class, () -> DebugLogWriter.await(receipt, Duration.ofMillis(20)));
            assertFalse(receipt.isDone());
            sink.release.countDown(); DebugLogWriter.await(receipt, WAIT);
            assertFalse(sink.written.contains("discard low")); assertTrue(sink.written.containsAll(List.of("keep high", "keep mid")));
            assertTrue(sink.forces.get() > 0);
        } finally { sink.release.countDown(); }
    }
    @Test void givenAllQueueSlotsConfirmedWhenFullThenRejectExplicitlyWithoutEviction() throws Exception {
        var sink = new BlockingSink(); var store = new DebugStateStore(directory, ignored -> {});
        try (var writer = new DebugLogWriter(sink, store, 1, Duration.ofMillis(10), record -> {}, ignored -> {})) {
            writer.offer(record("HIGH", "in flight"), null); assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
            var first = new CompletableFuture<Void>(); writer.offer(record("HIGH", "first"), first);
            var rejected = new CompletableFuture<Void>(); assertFalse(writer.offer(record("HIGH", "second"), rejected));
            assertThrows(IOException.class, () -> DebugLogWriter.await(rejected, WAIT));
            sink.release.countDown(); DebugLogWriter.await(first, WAIT); assertFalse(sink.written.contains("second"));
        } finally { sink.release.countDown(); }
    }
    @Test void givenConcurrentProducersWhenClosingThenWriteEachAcceptedMessageExactlyOnce() throws Exception {
        try (var debugger = new DefaultDebugger(directory, ignored -> {}, record -> {})) {
            try (var pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> jobs = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final int producer = t;
                    jobs.add(pool.submit(() -> { for (int i = 0; i < 100; i++) debugger.mid("[Concurrent 1] " + producer + ":" + i, null); }));
                }
                for (var job : jobs) job.get();
            }
            debugger.flush(WAIT);
        }
        Set<String> messages = new HashSet<>(); int lines = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".log")).toList()) {
                for (String line : Files.readAllLines(file)) { lines++; messages.add(line.substring(line.indexOf("[Concurrent"))); }
            }
        }
        assertEquals(400, lines); assertEquals(400, messages.size());
    }
    @Test void givenRotationWhenLimitReachedThenRetainBoundedLogsAndLeaveLegacyFilesAlone() throws Exception {
        Path legacy = directory.resolve("old-debug.yml"); Files.writeString(legacy, "old");
        try (var sink = new DebugLogWriter.FileSink(directory, 60, 120)) {
            for (int i = 0; i < 6; i++) sink.append(record("MID", "message " + i));
        }
        try (var files = Files.walk(directory)) { assertTrue(files.filter(p -> p.toString().endsWith(".log")).count() <= 2); }
        assertEquals("old", Files.readString(legacy));
    }
    @Test void givenIncompleteTailWhenRecoveringThenKeepAllCompleteRecords() throws Exception {
        Path log = directory.resolve("partial.log"); Files.writeString(log, "first\nsecond\npartial");
        DebugLogWriter.FileSink.repairTail(log); assertEquals("first\nsecond\n", Files.readString(log));
        Files.writeString(log, "broken"); DebugLogWriter.FileSink.repairTail(log); assertEquals("", Files.readString(log));
    }

    @Test void givenSeparateQuotasWhenRoutinePoolRotatesThenCriticalRecordsArePreserved() throws Exception {
        long quota = 256;
        try (var sink = new DebugLogWriter.SplitSink(directory, 100, quota)) {
            sink.append(record("HIGH", "critical-marker")); sink.force();
            String criticalBefore;
            try (var files = Files.list(directory.resolve("high-shutdown"))) { criticalBefore = Files.readString(files.filter(path -> path.toString().endsWith(".log")).findFirst().orElseThrow()); }
            assertTrue(criticalBefore.contains("critical-marker"));
            for (int i = 0; i < 20; i++) {
                sink.append(record(i % 2 == 0 ? "LOW" : "MID", "routine-record-" + i));
                assertTrue(poolSize(directory.resolve("low-mid")) <= quota);
            }
            sink.force();
            try (var files = Files.list(directory.resolve("high-shutdown"))) { assertEquals(criticalBefore, Files.readString(files.filter(path -> path.toString().endsWith(".log")).findFirst().orElseThrow())); }
            for (int i = 0; i < 20; i++) {
                sink.append(record(i % 2 == 0 ? "HIGH" : "SHUTDOWN", "critical-record-" + i));
                assertTrue(poolSize(directory.resolve("high-shutdown")) <= quota);
            }
        }
        // A new writer also accounts for old files rather than starting a fresh size counter.
        try (var sink = new DebugLogWriter.SplitSink(directory, 100, quota)) {
            sink.append(record("MID", "after-restart")); sink.append(record("HIGH", "after-restart"));
        }
        assertTrue(poolSize(directory.resolve("low-mid")) <= quota);
        assertTrue(poolSize(directory.resolve("high-shutdown")) <= quota);
    }
    private long poolSize(Path pool) throws IOException {
        long size = 0;
        try (var files = Files.list(pool)) { for (Path file : files.filter(p -> p.toString().endsWith(".log")).toList()) size += Files.size(file); }
        return size;
    }

    @Test void givenActivePoolOwnerWhenSecondWriterStartsThenNeverRepairOrDeleteItsFiles() throws Exception {
        try (var first = new DebugLogWriter.FileSink(directory, 100, 256);
             var second = new DebugLogWriter.FileSink(directory, 100, 256)) {
            first.append(record("HIGH", "owned")); first.force();
            assertThrows(IOException.class, () -> second.append(record("HIGH", "must not overwrite")));
        }
        try (var next = new DebugLogWriter.FileSink(directory, 100, 256)) { next.append(record("HIGH", "next owner")); }
    }
}
