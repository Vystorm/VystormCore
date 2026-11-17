package Vystorm.vystorm_core.network;

/**
 * A channel subscription; closing it stops delivery. Closing twice is harmless.
 *
 * @since 0.53.0
 */
public interface Subscription extends AutoCloseable {
    @Override void close();
}
