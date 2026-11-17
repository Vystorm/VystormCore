package paid.example;

import Vystorm.vystorm_core.licensing.LicensedModule;
import Vystorm.vystorm_core.licensing.ModuleSessionTest;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;

public final class Entry implements LicensedModule {
    public void start(Plugin owner, BooleanSupplier authorized) {
        ModuleSessionTest.starts++;
        ModuleSessionTest.authorization = authorized;
        if (ModuleSessionTest.failStart) throw new IllegalStateException("CUSTOMER-SECRET");
    }
    public void stop() { ModuleSessionTest.stops++; }
}
