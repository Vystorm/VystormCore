package Vystorm.vystorm_core.trace;

/**
 * {@code messages.show-source} in Core's config: chat lines sent through Core show their plugin on hover.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class MessageSource {
    private static volatile boolean enabled = true;

    private MessageSource() {}

    public static boolean enabled() { return enabled; }

    public static void configure(boolean value) { enabled = value; }
}
