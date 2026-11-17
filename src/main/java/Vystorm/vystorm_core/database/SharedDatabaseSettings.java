package Vystorm.vystorm_core.database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Settings of the shared Vystorm database ({@code database.shared} in Core's {@code config.yml}).
 * Immutable; {@link #toString()} never contains the password.
 *
 * @since 0.14.0
 */
public record SharedDatabaseSettings(
        boolean enabled,
        String type,
        String host,
        int port,
        String database,
        String username,
        String password,
        String sslMode,
        boolean allowPublicKeyRetrieval,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long keepaliveTimeMs,
        long leakDetectionThresholdMs,
        Map<String, String> properties) {

    /** JDBC driver used for MySQL and MariaDB; the server ships MySQL Connector/J. */
    public static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final Set<String> TYPES = Set.of("mysql", "mariadb");
    private static final Set<String> SSL_MODES = Set.of("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    public SharedDatabaseSettings {
        type = type == null ? "mysql" : type.trim().toLowerCase(Locale.ROOT);
        host = host == null ? "" : host.trim();
        database = database == null ? "" : database.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        sslMode = sslMode == null ? "PREFERRED" : sslMode.trim().toUpperCase(Locale.ROOT);
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** Disabled settings (no config section). */
    public static SharedDatabaseSettings disabled() {
        return new SharedDatabaseSettings(false, "mysql", "", 3306, "", "", "", "PREFERRED", false,
                8, 2, 10_000, 300_000, 900_000, 60_000, 0, Map.of());
    }

    /** Reads {@code database.shared}; a missing section means disabled. */
    public static SharedDatabaseSettings from(ConfigurationSection section) {
        if (section == null) return disabled();
        ConfigurationSection pool = section.getConfigurationSection("pool");
        Map<String, String> props = new LinkedHashMap<>();
        ConfigurationSection extra = section.getConfigurationSection("properties");
        if (extra != null) for (String key : extra.getKeys(false)) props.put(key, String.valueOf(extra.get(key)));
        return new SharedDatabaseSettings(
                section.getBoolean("enabled", false),
                section.getString("type", "mysql"),
                section.getString("host", ""),
                section.getInt("port", 3306),
                section.getString("database", ""),
                section.getString("username", ""),
                section.getString("password", ""),
                section.getString("ssl-mode", "PREFERRED"),
                section.getBoolean("allow-public-key-retrieval", false),
                pool == null ? 8 : pool.getInt("maximum-size", 8),
                pool == null ? 2 : pool.getInt("minimum-idle", 2),
                pool == null ? 10_000 : pool.getLong("connection-timeout-ms", 10_000),
                pool == null ? 300_000 : pool.getLong("idle-timeout-ms", 300_000),
                pool == null ? 900_000 : pool.getLong("max-lifetime-ms", 900_000),
                pool == null ? 60_000 : pool.getLong("keepalive-time-ms", 60_000),
                pool == null ? 0 : pool.getLong("leak-detection-threshold-ms", 0),
                props);
    }

    /** Problems that prevent a connection attempt; empty if the settings are usable. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (!TYPES.contains(type)) problems.add("type must be mysql or mariadb, not '" + type + "'");
        if (host.isEmpty()) problems.add("host is empty");
        if (port < 1 || port > 65535) problems.add("port out of range: " + port);
        if (database.isEmpty()) problems.add("database is empty");
        else if (!database.matches("[A-Za-z0-9_$-]{1,64}")) problems.add("database name contains invalid characters");
        if (username.isEmpty()) problems.add("username is empty");
        if (!SSL_MODES.contains(sslMode)) problems.add("ssl-mode must be one of " + SSL_MODES + ", not '" + sslMode + "'");
        if (maximumPoolSize < 1 || maximumPoolSize > 100) problems.add("pool.maximum-size must be 1..100");
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) problems.add("pool.minimum-idle must be 0..maximum-size");
        if (connectionTimeoutMs < 250) problems.add("pool.connection-timeout-ms must be >= 250");
        if (maxLifetimeMs != 0 && maxLifetimeMs < 30_000) problems.add("pool.max-lifetime-ms must be 0 or >= 30000");
        if (keepaliveTimeMs != 0 && keepaliveTimeMs < 30_000) problems.add("pool.keepalive-time-ms must be 0 or >= 30000");
        return problems;
    }

    /** JDBC URL without credentials. */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /** Driver properties (Connector/J), user overrides from {@code properties} win. */
    public Map<String, String> driverProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("sslMode", sslMode);
        props.put("allowPublicKeyRetrieval", String.valueOf(allowPublicKeyRetrieval));
        props.put("characterEncoding", "UTF-8");
        props.put("tcpKeepAlive", "true");
        props.put("connectTimeout", String.valueOf(connectionTimeoutMs));
        props.put("cachePrepStmts", "true");
        props.put("prepStmtCacheSize", "250");
        props.put("prepStmtCacheSqlLimit", "2048");
        props.put("useServerPrepStmts", "true");
        props.put("useLocalSessionState", "true");
        props.put("rewriteBatchedStatements", "true");
        props.put("cacheResultSetMetadata", "true");
        props.put("cacheServerConfiguration", "true");
        props.put("elideSetAutoCommits", "true");
        props.put("maintainTimeStats", "false");
        props.putAll(properties);
        return props;
    }

    @Override
    public String toString() {
        return "SharedDatabaseSettings[enabled=" + enabled + ", type=" + type + ", url=" + jdbcUrl() + ", user=" + username
                + ", sslMode=" + sslMode + ", pool=" + minimumIdle + ".." + maximumPoolSize + "]";
    }
}
