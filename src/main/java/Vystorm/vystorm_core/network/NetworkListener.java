package Vystorm.vystorm_core.network;

/**
 * Receives network messages on the server main thread.
 *
 * @since 0.53.0
 */
@FunctionalInterface
public interface NetworkListener {
    /**
     * @param fromServer name of the sending server
     * @param payload    a private copy of the bytes
     */
    void onMessage(String fromServer, byte[] payload);
}
