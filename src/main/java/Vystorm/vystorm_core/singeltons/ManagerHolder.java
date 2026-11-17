package Vystorm.vystorm_core.singeltons;

import java.util.Objects;

/**
 * Holder of one manager of {@link Vystorm.vystorm_core.API}: the current implementation and its override seam.
 * <p>
 * The older holders in this package are one class per manager with the same few lines. Managers added since 0.64.0
 * share this one. Reads are lock-free ({@code volatile}); {@link #override} may be called from any thread.
 *
 * @param <T> the manager interface
 * @since 0.64.0
 */
public final class ManagerHolder<T> {
    private volatile T instance;

    /** @param initial the default implementation, never null */
    public ManagerHolder(T initial) { this.instance = Objects.requireNonNull(initial, "initial"); }

    /** The current implementation, never null. */
    public T getInstance() { return instance; }

    /** Replaces the implementation. @throws NullPointerException if {@code replacement} is null */
    public void override(T replacement) { this.instance = Objects.requireNonNull(replacement, "replacement"); }
}
