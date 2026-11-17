package Vystorm.vystorm_core.database;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The shared MySQL/MariaDB database of all Vystorm plugins, configured once in Core's {@code config.yml}
 * ({@code database.shared}). Every plugin keeps its own tables, named {@code <prefix>_<table>} via {@link #tableName}.
 *
 * <pre>{@code
 * Optional<DataSource> db = api.database().shared();          // or SharedDatabase.dataSource()
 * if (db.isEmpty()) { useFlatFiles(); return; }              // disabled, still connecting or failed
 * String table = SharedDatabase.tableName("dinv", "stash");   // -> "dinv_stash"
 * try (Connection c = db.get().getConnection(); ...) { ... }  // never on the server thread
 * }</pre>
 *
 * <p>Core tests the connection asynchronously after enable. Plugins enabling right after Core may see
 * {@link Status#CONNECTING}; {@link #whenReady()} completes once the outcome is known. Hand the connection back
 * (try-with-resources) quickly: the pool is shared by all plugins and small. Never close the {@link DataSource} itself.
 * All methods are thread-safe.
 *
 * @since 0.14.0
 */
public final class SharedDatabase {
    /** ID under which the pool is also registered in the legacy {@code api.database().getDataSource(ID)}. */
    public static final String ID = "vystorm";

    public enum Status { DISABLED, CONNECTING, AVAILABLE, FAILED, CLOSED }

    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9]{1,15}");
    private static final Pattern TABLE = Pattern.compile("[a-z0-9][a-z0-9_]*");
    private static final int MAX_TABLE_NAME = 64;
    private static final Object LOCK = new Object();

    private static Status status = Status.DISABLED;
    private static DataSource dataSource;
    private static AutoCloseable closer;
    private static String failure;
    private static CompletableFuture<Optional<DataSource>> ready = CompletableFuture.completedFuture(Optional.empty());
    private static long generation;

    private SharedDatabase() {}

    /** The shared pool if it is connected and tested; empty if disabled, still connecting, failed or closed. */
    public static Optional<DataSource> dataSource() {
        synchronized (LOCK) { return status == Status.AVAILABLE ? Optional.of(dataSource) : Optional.empty(); }
    }

    /** Whether {@link #dataSource()} is present. */
    public static boolean available() {
        synchronized (LOCK) { return status == Status.AVAILABLE; }
    }

    public static Status status() {
        synchronized (LOCK) { return status; }
    }

    /** Reason of the last failure (no credentials), if {@link Status#FAILED}. */
    public static Optional<String> failure() {
        synchronized (LOCK) { return status == Status.FAILED ? Optional.ofNullable(failure) : Optional.empty(); }
    }

    /**
     * Completes with the pool once the connection test is done, or empty when disabled, failed or closed.
     * Callbacks run on Core's init thread (or immediately), never assume the server thread.
     */
    public static CompletableFuture<Optional<DataSource>> whenReady() {
        synchronized (LOCK) { return ready.copy(); }
    }

    /**
     * Safe table name {@code <prefix>_<table>}, lower case. {@code prefix}: 2-16 chars {@code [a-z][a-z0-9]*}, unique per
     * plugin (e.g. {@code dinv}, {@code market}); {@code table}: {@code [a-z0-9_]}; total length at most 64.
     *
     * @throws IllegalArgumentException if a part is invalid (names are never silently rewritten)
     */
    public static String tableName(String prefix, String table) {
        String p = prefix(prefix);
        String t = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
        if (!TABLE.matcher(t).matches())
            throw new IllegalArgumentException("Invalid table name '" + table + "': [a-z0-9][a-z0-9_]*");
        String name = p + "_" + t;
        if (name.length() > MAX_TABLE_NAME)
            throw new IllegalArgumentException("Table name too long (" + name.length() + " > 64): " + name);
        return name;
    }

    /** Validated, lower-case plugin prefix (see {@link #tableName}). */
    static String prefix(String prefix) {
        String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (!PREFIX.matcher(p).matches())
            throw new IllegalArgumentException("Invalid table prefix '" + prefix + "': 2-16 chars, [a-z][a-z0-9]*");
        return p;
    }

    /**
     * Starts the pool asynchronously (Core only). Returns immediately; the result is reported through
     * {@link #status()}/{@link #whenReady()} and the log. {@code onAvailable} runs on the init thread after a successful test.
     */
    public static void start(SharedDatabaseSettings settings, Logger logger, Consumer<DataSource> onAvailable) {
        Logger log = logger != null ? logger : Logger.getLogger("vystorm_core.database");
        shutdown();
        if (settings == null || !settings.enabled()) {
            synchronized (LOCK) { status = Status.DISABLED; ready = CompletableFuture.completedFuture(Optional.empty()); }
            DebugMessages.low("[SharedDatabase] Shared database disabled (database.shared.enabled=false)");
            return;
        }
        List<String> problems = settings.problems();
        if (!problems.isEmpty()) {
            String reason = "invalid config: " + String.join("; ", problems);
            synchronized (LOCK) { status = Status.FAILED; failure = reason; ready = CompletableFuture.completedFuture(Optional.empty()); }
            log.warning("[SharedDatabase] Shared database not available, " + reason);
            DebugMessages.high("[SharedDatabase] Shared database not available, " + reason);
            return;
        }
        CompletableFuture<Optional<DataSource>> future = new CompletableFuture<>();
        long gen;
        synchronized (LOCK) { status = Status.CONNECTING; failure = null; ready = future; gen = ++generation; }
        DebugMessages.mid("[SharedDatabase] Connecting to " + settings);
        Thread.ofPlatform().daemon().name("Vystorm-SharedDB-init").start(() -> connect(settings, log, onAvailable, future, gen));
    }

    private static void connect(SharedDatabaseSettings settings, Logger log, Consumer<DataSource> onAvailable,
                                CompletableFuture<Optional<DataSource>> future, long gen) {
        com.zaxxer.hikari.HikariDataSource pool = null;
        try {
            pool = SharedPool.open(settings);
            String info = probe(pool, settings, log);
            synchronized (LOCK) {
                if (generation != gen) { pool.close(); future.complete(Optional.empty()); return; }
                dataSource = pool;
                closer = pool;
                status = Status.AVAILABLE;
            }
            log.info("[SharedDatabase] Shared database available: " + info);
            DebugMessages.mid("[SharedDatabase] Shared database available: " + info);
            if (onAvailable != null) {
                try { onAvailable.accept(pool); }
                catch (RuntimeException | LinkageError hookFailure) { DebugMessages.high("[SharedDatabase] onAvailable hook failed", hookFailure); }
            }
            future.complete(Optional.of(pool));
        } catch (Exception | LinkageError error) {
            if (pool != null) {
                try { pool.close(); } catch (RuntimeException ignored) {}
            }
            String reason = describe(error);
            synchronized (LOCK) {
                if (generation == gen) { status = Status.FAILED; failure = reason; }
            }
            log.warning("[SharedDatabase] Shared database NOT available (" + settings.jdbcUrl() + ", user "
                    + settings.username() + "): " + reason + ". Plugins fall back to their local storage.");
            DebugMessages.high("[SharedDatabase] Connection test failed for " + settings, error);
            future.complete(Optional.empty());
        }
    }

    /** Opens one connection, checks it and warns about pool timings the server would cut off. */
    private static String probe(DataSource pool, SharedDatabaseSettings settings, Logger log) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            if (!connection.isValid(5)) throw new SQLException("connection is not valid");
            DatabaseMetaData meta = connection.getMetaData();
            String info = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion() + " at " + settings.jdbcUrl()
                    + " (pool " + settings.minimumIdle() + ".." + settings.maximumPoolSize() + ")";
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT @@wait_timeout, @@max_user_connections")) {
                if (row.next()) {
                    long waitTimeoutMs = row.getLong(1) * 1000L;
                    long maxUserConnections = row.getLong(2);
                    info += ", wait_timeout=" + (waitTimeoutMs / 1000) + "s";
                    if (maxUserConnections > 0) info += ", max_user_connections=" + maxUserConnections;
                    if (waitTimeoutMs > 0 && settings.maxLifetimeMs() > 0 && settings.maxLifetimeMs() > waitTimeoutMs - 30_000)
                        log.warning("[SharedDatabase] pool.max-lifetime-ms (" + settings.maxLifetimeMs()
                                + ") should be at least 30 s below the server wait_timeout (" + waitTimeoutMs + " ms)");
                    if (waitTimeoutMs > 0 && settings.keepaliveTimeMs() > 0 && settings.keepaliveTimeMs() >= waitTimeoutMs)
                        log.warning("[SharedDatabase] pool.keepalive-time-ms (" + settings.keepaliveTimeMs()
                                + ") is not below the server wait_timeout (" + waitTimeoutMs + " ms)");
                    if (maxUserConnections > 0 && settings.maximumPoolSize() > maxUserConnections)
                        log.warning("[SharedDatabase] pool.maximum-size (" + settings.maximumPoolSize()
                                + ") exceeds the server max_user_connections (" + maxUserConnections + ")");
                }
            } catch (SQLException variables) {
                DebugMessages.low("[SharedDatabase] Server variables not readable: " + variables.getMessage());
            }
            return info;
        }
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (root != error) text += " (cause " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        return text;
    }

    /** Closes the pool (Core disable). Idempotent; afterwards {@link #status()} is {@link Status#CLOSED} (or DISABLED). */
    public static void shutdown() {
        AutoCloseable toClose;
        CompletableFuture<Optional<DataSource>> pending;
        synchronized (LOCK) {
            generation++;
            toClose = closer;
            pending = ready;
            closer = null;
            dataSource = null;
            if (status != Status.DISABLED) status = Status.CLOSED;
            ready = CompletableFuture.completedFuture(Optional.empty());
        }
        pending.complete(Optional.empty());
        if (toClose != null) {
            try {
                toClose.close();
                DebugMessages.mid("[SharedDatabase] Shared database pool closed");
            } catch (Exception failure) {
                Logger.getLogger("vystorm_core.database").log(Level.WARNING, "[SharedDatabase] Closing the pool failed", failure);
            }
        }
    }
}
