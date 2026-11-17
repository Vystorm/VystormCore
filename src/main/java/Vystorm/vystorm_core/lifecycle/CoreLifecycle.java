package Vystorm.vystorm_core.lifecycle;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.combat.CombatRegistry;
import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.item.ItemServices;
import Vystorm.vystorm_core.mob.MobContextServices;
import Vystorm.vystorm_core.mob.MobServices;
import Vystorm.vystorm_core.mob.RelationServices;
import Vystorm.vystorm_core.mob.extension.MobExtensions;
import Vystorm.vystorm_core.party.PartyServices;
import Vystorm.vystorm_core.progression.DisciplineServices;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.runtime.RuntimeActionHub;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import Vystorm.vystorm_core.world.WorldEnvironmentRegistry;
import Vystorm.vystorm_core.worldevent.WorldEventServices;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Core-interner Lebenszyklus: Beim Disable eines Plugins werden dessen Provider aus allen Core-Registries entfernt,
 * unabhängig davon, ob die jeweilige Registry schon einmal benutzt wurde. Wird von Core in {@code onEnable} registriert.
 * Alle Aufräumschritte sind idempotent; ein Fehler in einem Schritt stoppt die übrigen nicht.
 */
public final class CoreLifecycle implements Listener {
    @SuppressWarnings("deprecation")
    private static final List<Step> STEPS = List.of(
            new Step("EventStages", owner -> Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages.shared().removeAll(owner)),
            new Step("ChatCallbacks", Vystorm.vystorm_core.compat.ClickActions::release),
            new Step("ClientBiomes", Vystorm.vystorm_core.packet.ClientBiomes::release),
            new Step("Visuals", Vystorm.vystorm_core.visual.Visuals::release),
            new Step("LicensedModules", Vystorm.vystorm_core.licensing.LicensedModules::release),
            new Step("ConfigEditor", Vystorm.vystorm_core._uiMethods.settings.ConfigEditor::unregister),
            new Step("PlayerMenu", Vystorm.vystorm_core.playermenu.PlayerMenu::unregister),
            new Step("Profiles", Vystorm.vystorm_core.profile.Profiles::unregister),
            new Step("RoutineMessages", Vystorm.vystorm_core._uiMethods.notifications.RoutineMessages::release),
            new Step("FeatureDiagnostics", FeatureDiagnostics::unregister),
            new Step("UpdateSources", UpdateSources::unregister),
            new Step("MigrationPreviews", Vystorm.vystorm_core.storage.MigrationPreviews::unregister),
            new Step("MobServices", MobServices::unregister),
            new Step("RelationServices", RelationServices::unregisterAll),
            new Step("MobContextServices", MobContextServices::unregisterAll),
            new Step("MobExtensions", MobExtensions::unregisterAll),
            new Step("ItemServices", ItemServices::unregister),
            new Step("ItemRefs", ItemRefs::unregisterAll),
            new Step("LootTables", Vystorm.vystorm_core.loot.LootTables::release),
            new Step("LootLuck", Vystorm.vystorm_core.loot.LootLuck::release),
            new Step("MobLoot", Vystorm.vystorm_core.loot.MobLoot::release),
            new Step("DisciplineServices", DisciplineServices::unregister),
            new Step("CharacterServices", Vystorm.vystorm_core.progression.CharacterServices::unregister),
            new Step("PartyServices", PartyServices::unregister),
            new Step("WorldEvents", owner -> Vystorm.vystorm_core.worldevent.WorldEvents.get().release(owner)),
            new Step("WorldEventServices", WorldEventServices::unregister),
            new Step("CombatRegistry", CombatRegistry::unregisterAll),
            new Step("RuntimeActionHub", owner -> RuntimeActionHub.get().unregisterOwner(owner)),
            new Step("RuntimeContextHub", owner -> RuntimeContextHub.get().unregisterOwner(owner)),
            new Step("WorldEnvironmentRegistry", owner -> WorldEnvironmentRegistry.get().unregisterOwner(owner)),
            new Step("RegionService", owner -> RegionService.get().unregisterOwner(owner)),
            new Step("SpawnPointService", owner -> Vystorm.vystorm_core.spawn.SpawnPointService.get().unregisterOwner(owner)),
            new Step("CoreCommands", Vystorm.vystorm_core.commands.CoreCommands::unregister),
            new Step("WebServices", Vystorm.vystorm_core.web.WebServices::unregister),
            new Step("ClientUi", Vystorm.vystorm_core.web.ClientUi::release),
            new Step("Panels", Vystorm.vystorm_core.panel.Panels::release),
            new Step("Screens", owner -> { try { Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().release(owner); } catch (IllegalStateException notRunning) { } }),
            new Step("ManagedWorlds", Vystorm.vystorm_core.worlds.ManagedWorlds::release),
            new Step("Localization", owner -> Vystorm.vystorm_core.lang.Localization.get().unregister(owner)));

    private record Step(String name, Consumer<Plugin> action) {}

    /** Entfernt alle Registrierungen des Plugins aus den Core-Registries. */
    public static void releaseAll(Plugin owner) {
        if (owner == null) return;
        for (Step step : STEPS) {
            try {
                step.action().accept(owner);
            } catch (RuntimeException | LinkageError failure) {
                DebugMessages.high("[CoreLifecycle] Cleanup " + step.name() + " for " + owner.getName() + " failed", failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) { releaseAll(event.getPlugin()); }
}
