package Vystorm.vystorm_core.network.core;

/**
 * Platform logger seam (Bukkit, Velocity and BungeeCord loggers differ). Messages never contain key material.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public interface NetworkLog {
    void info(String message);

    void warn(String message);

    NetworkLog SILENT = new NetworkLog() {
        @Override public void info(String message) {}
        @Override public void warn(String message) {}
    };

    static NetworkLog of(java.util.logging.Logger logger) {
        return new NetworkLog() {
            @Override public void info(String message) { logger.info("[Network] " + message); }
            @Override public void warn(String message) { logger.warning("[Network] " + message); }
        };
    }
}
