package Vystorm.vystorm_core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;

/** Builds the Hikari pool for the shared database. Kept apart so {@link SharedDatabase} itself does not need Hikari. */
final class SharedPool {
    static final String POOL_NAME = "Vystorm-Shared";

    private SharedPool() {}

    static HikariConfig config(SharedDatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setDriverClassName(SharedDatabaseSettings.DRIVER);
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(5_000, Math.max(250, settings.connectionTimeoutMs() - 1)));
        config.setIdleTimeout(settings.idleTimeoutMs());
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setKeepaliveTime(settings.keepaliveTimeMs());
        config.setLeakDetectionThreshold(settings.leakDetectionThresholdMs());
        // Do not connect in the constructor; SharedDatabase tests the connection itself and reports the failure.
        config.setInitializationFailTimeout(-1);
        for (Map.Entry<String, String> property : settings.driverProperties().entrySet())
            config.addDataSourceProperty(property.getKey(), property.getValue());
        return config;
    }

    static HikariDataSource open(SharedDatabaseSettings settings) {
        return new HikariDataSource(config(settings));
    }
}
