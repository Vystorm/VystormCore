package Vystorm.vystorm_core.network.core;

import java.time.Duration;
import java.util.List;

/**
 * Hub listener settings; {@link #defaults()} holds the values fixed by the spec.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record HubConfig(String bindHost, int port, List<String> allowCidrs, int maxPending,
                        Duration handshakeTimeout, Duration heartbeat, Duration idleTimeout) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 25599;

    public HubConfig {
        allowCidrs = List.copyOf(allowCidrs);
    }

    public static HubConfig defaults() {
        return new HubConfig(DEFAULT_HOST, DEFAULT_PORT, List.of(), 64,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    public HubConfig withAddress(String host, int newPort, List<String> allow) {
        return new HubConfig(host, newPort, allow, maxPending, handshakeTimeout, heartbeat, idleTimeout);
    }
}
