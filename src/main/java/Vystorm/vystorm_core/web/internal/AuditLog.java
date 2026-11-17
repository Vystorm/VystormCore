package Vystorm.vystorm_core.web.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Audit-Log der Web-Plattform: Logins, Logouts, Sitzungsentzug, jede ändernde API-Anfrage und Tunnel-Ereignisse.
 * Eine Zeile je Ereignis ({@code zeit event=… player=… uuid=… route=… ip=… result=… detail=…}), Werte ohne Steuerzeichen.
 * Geschrieben wird auf einem eigenen Thread mit begrenzter Warteschlange; die Datei rotiert bei {@code maxFileBytes}
 * ({@code audit.log} → {@code audit.log.1} …), es bleiben höchstens {@code files} Dateien. Tokens, Cookies und
 * Anfragekörper werden nie geschrieben. Core-intern.
 *
 * @since 0.17.0
 */
public final class AuditLog implements AutoCloseable {
    private final Path file;
    private final long maxFileBytes;
    private final int files;
    private final boolean enabled;
    private final Logger logger;
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();

    public AuditLog(Path dir, long maxFileBytes, int files, boolean enabled, Logger logger) {
        this.file = dir.resolve("audit.log");
        this.maxFileBytes = maxFileBytes;
        this.files = files;
        this.enabled = enabled;
        this.logger = logger;
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4096),
                r -> { Thread t = new Thread(r, "Vystorm-Web-Audit"); t.setDaemon(true); return t; },
                (r, pool) -> dropped.incrementAndGet());
    }

    /** Ein Ereignis; {@code null}-Werte werden weggelassen. */
    public void log(String event, UUID player, String name, String route, String ip, String result, String detail) {
        if (!enabled || writer.isShutdown()) return;
        StringBuilder line = new StringBuilder(160).append(Instant.now()).append(" event=").append(clean(event));
        if (name != null) line.append(" player=").append(clean(name));
        if (player != null) line.append(" uuid=").append(player);
        if (route != null) line.append(" route=").append(clean(route));
        if (ip != null) line.append(" ip=").append(clean(ip));
        if (result != null) line.append(" result=").append(clean(result));
        if (detail != null && !detail.isEmpty()) line.append(" detail=\"").append(clean(detail, true).replace("\"", "'")).append('"');
        line.append('\n');
        String text = line.toString();
        writer.execute(() -> write(text));
    }

    /** Zahl der wegen voller Warteschlange verworfenen Einträge. */
    public long dropped() { return dropped.get(); }

    /** Wartet auf die ausstehenden Einträge (für Tests und das Herunterfahren). */
    public void flush(long timeoutMs) {
        if (writer.isShutdown()) return;
        var done = new java.util.concurrent.CountDownLatch(1);
        try {
            writer.execute(done::countDown);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException rejected) {
            // Warteschlange voll: nichts zu tun.
        }
    }

    @Override public void close() {
        writer.shutdown();
        try { writer.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void write(String line) {
        try {
            Files.createDirectories(file.getParent());
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (Files.exists(file) && Files.size(file) + bytes.length > maxFileBytes) rotate();
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                out.write(bytes);
            }
        } catch (IOException | RuntimeException failure) {
            if (logger != null) logger.log(Level.WARNING, "[Web] Audit log write failed: " + failure);
        }
    }

    private void rotate() throws IOException {
        Path oldest = file.resolveSibling("audit.log." + (files - 1));
        if (files <= 1) { Files.deleteIfExists(file); return; }
        Files.deleteIfExists(oldest);
        for (int i = files - 2; i >= 1; i--) {
            Path from = file.resolveSibling("audit.log." + i);
            if (Files.exists(from)) Files.move(from, file.resolveSibling("audit.log." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(file, file.resolveSibling("audit.log.1"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Entfernt Steuerzeichen und Leerraum, kürzt auf 200 Zeichen. */
    static String clean(String value) { return clean(value, false); }

    static String clean(String value, boolean keepSpaces) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 200));
        for (int i = 0; i < value.length() && out.length() < 200; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? '_' : c == ' ' && !keepSpaces ? '_' : c);
        }
        return out.toString();
    }

    Path file() { return file; }
}
