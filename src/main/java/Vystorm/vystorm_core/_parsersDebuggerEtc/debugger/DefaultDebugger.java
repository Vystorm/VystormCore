package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types.*;
import Vystorm.vystorm_core.main;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class DefaultDebugger implements DebugInterface {
    private static final java.util.regex.Pattern MARKER = java.util.regex.Pattern.compile("^\\s*\\[([^]\\r\\n]+)]");
    private static final int MAX_MESSAGE_CHARS = 8192;
    private final DebugStateStore states;
    private final DebugLogWriter writer;

    public DefaultDebugger() {
        this(main.getPlugin().getDataFolder().toPath().resolve("debug"),
                message -> Logger.getLogger("vystorm_core.debug-fallback").warning(message), defaultOutput());
    }
    DefaultDebugger(Path directory, Consumer<String> emergency, Consumer<DebugLogWriter.Record> output) {
        this(directory, emergency, output, new DebugLogWriter.SplitSink(directory, 8 * 1024 * 1024, 512L * 1024 * 1024), 1024, Duration.ofMillis(250));
    }
    DefaultDebugger(Path directory, Consumer<String> emergency, Consumer<DebugLogWriter.Record> output,
                    DebugLogWriter.Sink sink, int capacity, Duration interval) {
        states = new DebugStateStore(directory, emergency);
        // Datei: alles (je Klasse schaltbar). Konsole: nur freigegebene Stufen (Standard HIGH und SHUTDOWN).
        Consumer<DebugLogWriter.Record> console = record -> { if (states.console(record.level())) output.accept(record); };
        writer = new DebugLogWriter(sink, states, capacity, interval, console, emergency);
    }
    private static Consumer<DebugLogWriter.Record> defaultOutput() {
        Low low = new Low(); Mid mid = new Mid(); High high = new High(); Schutdown shutdown = new Schutdown();
        return record -> {
            switch (record.level()) {
                case "LOW" -> low.debug(record.message(), record.time());
                case "MID" -> mid.debug(record.message(), record.time());
                case "HIGH" -> high.debug(record.message(), record.time());
                case "SHUTDOWN" -> shutdown.debug(record.message(), record.time());
                default -> throw new IllegalArgumentException("Unknown debug level");
            }
        };
    }
    @SuppressWarnings("deprecation")
    @Override public void low(String message, LocalDateTime now) { emit(message, "LOW", now, null); }
    @SuppressWarnings("deprecation")
    @Override public void mid(String message, LocalDateTime now) { emit(message, "MID", now, null); }
    @SuppressWarnings("deprecation")
    @Override public void high(String message, LocalDateTime now) { emit(message, "HIGH", now, null); }
    @Override public void shutdown(String message, LocalDateTime now) { emit(message, "SHUTDOWN", now, null); }

    private boolean emit(String message, String level, LocalDateTime date, CompletableFuture<Void> receipt) {
        if (writer.isWriterThread()) return false; // A custom output callback must not recursively feed the writer.
        String text = Objects.requireNonNullElse(message, "null");
        if (text.length() > MAX_MESSAGE_CHARS) {
            if (receipt != null) {
                receipt.completeExceptionally(new IOException("Confirmed debug message exceeds 8192 characters"));
                return true; // Waiter observes an explicit failure, never confirmation of truncated content.
            }
            text = text.substring(0, MAX_MESSAGE_CHARS) + " [truncated]";
        }
        if (!states.enabled(extractPointer(text), level)) return false;
        writer.offer(new DebugLogWriter.Record(level, text, date == null ? LocalDateTime.now() : date), receipt);
        return true;
    }
    @Override public boolean highConfirmed(String message, LocalDateTime date, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        validateWait(timeout);
        CompletableFuture<Void> receipt = new CompletableFuture<>();
        if (!emit(message, "HIGH", date, receipt)) return false;
        DebugLogWriter.await(receipt, timeout);
        return true;
    }
    @Override public void flush(Duration timeout) throws IOException, InterruptedException, TimeoutException {
        validateWait(timeout);
        DebugLogWriter.await(writer.barrier(false), timeout);
    }
    @Override public void reloadStates(Duration timeout) throws IOException, InterruptedException, TimeoutException {
        validateWait(timeout);
        DebugLogWriter.await(writer.barrier(true), timeout);
    }
    private void validateWait(Duration timeout) {
        if (writer.isWriterThread()) throw new IllegalStateException("Cannot await the debug writer from its own callback");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        timeout.toNanos(); // Validate overflow before enqueueing work.
    }
    @Override public void close() { writer.close(); }

    static String extractPointer(String message) {
        if (message == null) return "Unknown";
        java.util.regex.Matcher marker = MARKER.matcher(message);
        if (!marker.find()) return "Unknown";
        String pointer = marker.group(1).replaceFirst("(?:\\s+|/)\\d+$", "").trim();
        return pointer.isEmpty() || pointer.length() > 128 ? "Unknown" : pointer;
    }
}
