package Vystorm.vystorm_core.network;

/**
 * One server of the network.
 *
 * @param name      name in the proxy configuration
 * @param connected Vystorm Core on that server is connected to the hub
 * @param players   players currently on it
 * @since 0.53.0
 */
public record NetworkServer(String name, boolean connected, int players) {}
