package Vystorm.vystorm_core.licensing;

import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;

/** Entry point implemented only in the remotely supplied module, never in the distributed paid-plugin jar.
 * @since 0.28.0 */
public interface LicensedModule {
    /** Server thread. Check authorized before every protected operation, including tasks/listeners.
     * Register work under owner. No work, threads or registrations in constructors/static initializers.
     * Do not use JavaPlugin.getProvidingPlugin(getClass()); the remote class loader is not Paper's plugin loader. */
    void start(Plugin owner, BooleanSupplier authorized) throws Exception;

    /** Server thread; also called after a partially failed start. Stop tasks, unregister listeners/providers and
     * release references. Must be safe to call once after a partial initialization. Do not delete customer data. */
    void stop() throws Exception;
}
