package Vystorm.vystorm_core._uiMethods.admin;

import Vystorm.vystorm_core.lifecycle.FeatureDiagnostics;
import Vystorm.vystorm_core.lifecycle.FeatureDiagnostics.State;
import Vystorm.vystorm_core.lifecycle.FeatureDiagnostics.Status;
import Vystorm.vystorm_core.database.SharedDatabase;
import Vystorm.vystorm_core.mob.MobServices;
import Vystorm.vystorm_core.web.ClientServices;
import org.bukkit.plugin.Plugin;

/** Core's real feature probes; plugin-owned suspended stores add their own probes. */
final class CoreFeatureDiagnostics {
    private CoreFeatureDiagnostics() {}
    static void register(Plugin core) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        FeatureDiagnostics.register(core, "mob-provider", p -> new Status("mob-provider", MobServices.providerName().orElse("—"),
                MobServices.available() ? State.ACTIVE : State.FALLBACK, lang.ref(MobServices.available() ? "diagnostics.mob-active" : "diagnostics.mob-fallback")));
        FeatureDiagnostics.register(core, "shared-database", p -> new Status("shared-database", "Vystorm Core",
                SharedDatabase.available() ? State.ACTIVE : State.UNAVAILABLE,
                lang.ref("diagnostics.database-" + SharedDatabase.status().name().toLowerCase(java.util.Locale.ROOT))));
        FeatureDiagnostics.register(core, "client-panels", p -> {
            String reason = p == null ? "target-required" : !ClientServices.enabled() ? "client-disabled"
                    : !ClientServices.hasClient(p) ? "client-absent"
                    : !Vystorm.vystorm_core.panel.Panels.of(core).available(p) ? "client-old" : "client-active";
            return new Status("client-panels", "Vystorm Client", reason.equals("client-active") ? State.ACTIVE : State.FALLBACK, lang.ref("diagnostics." + reason));
        });
        FeatureDiagnostics.register(core, "client-hud", p -> new Status("client-hud", "Vystorm Client",
                ClientServices.uiAvailable(p, ClientServices.CAP_HUD) && !ClientServices.uiHidden(p) ? State.ACTIVE : State.FALLBACK,
                lang.ref(p == null ? "diagnostics.target-required" : ClientServices.uiHidden(p) ? "diagnostics.hud-hidden"
                        : ClientServices.uiAvailable(p, ClientServices.CAP_HUD) ? "diagnostics.client-active" : "diagnostics.hud-fallback")));
    }
}
