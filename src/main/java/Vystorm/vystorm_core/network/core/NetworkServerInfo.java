package Vystorm.vystorm_core.network.core;

/**
 * One backend server as the network sees it.
 *
 * @param name      server name from the proxy configuration
 * @param connected Core on that server is connected to the hub
 * @param players   players the proxy currently routes to it
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record NetworkServerInfo(String name, boolean connected, int players) {}
