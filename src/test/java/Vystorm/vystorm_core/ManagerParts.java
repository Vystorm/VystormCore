package Vystorm.vystorm_core;

import java.util.List;
import java.util.Set;

/**
 * The manager parts that mirror a static class: interface, static class, how a plugin reaches it, and the static
 * methods that are deliberately not mirrored (Core lifecycle hooks). {@link ManagerMirrorTest} checks every row, so a
 * new public static method on one of these classes fails the build until it is mirrored or listed here.
 */
final class ManagerParts {
    record Part(Class<?> manager, Class<?> statics, String access, Set<String> notMirrored) {
        Part(Class<?> manager, Class<?> statics, String access, String... notMirrored) {
            this(manager, statics, access, Set.of(notMirrored));
        }
    }

    private ManagerParts() {}

    static final List<Part> ALL = List.of(
            new Part(Vystorm.vystorm_core.compat.PlatformCompatInterface.class, Vystorm.vystorm_core.compat.PlatformCompat.class, "api.compat().platform()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.blocks.BlockWritesInterface.class, Vystorm.vystorm_core.blocks.BlockWrites.class, "api.blocks()", "release", "shutdown"),
            new Part(Vystorm.vystorm_core.compat.ServerCompatInterface.class, Vystorm.vystorm_core.compat.ServerCompat.class, "api.compat().server()"),
            new Part(Vystorm.vystorm_core.compat.InventoryCompatInterface.class, Vystorm.vystorm_core.compat.InventoryCompat.class, "api.compat().inventory()"),
            new Part(Vystorm.vystorm_core.compat.GameplayCompatInterface.class, Vystorm.vystorm_core.compat.GameplayCompat.class, "api.compat().gameplay()", "initialize"),
            new Part(Vystorm.vystorm_core.compat.WorldCompatInterface.class, Vystorm.vystorm_core.compat.WorldCompat.class, "api.compat().world()"),
            new Part(Vystorm.vystorm_core.compat.StorageCompatInterface.class, Vystorm.vystorm_core.compat.StorageCompat.class, "api.compat().storage()"),
            new Part(Vystorm.vystorm_core.compat.ItemCompatInterface.class, Vystorm.vystorm_core.compat.ItemCompat.class, "api.compat().items()"),
            new Part(Vystorm.vystorm_core.compat.MobNavigationInterface.class, Vystorm.vystorm_core.compat.MobNavigation.class, "api.compat().navigation()"),
            new Part(Vystorm.vystorm_core.compat.PassengerTeleportInterface.class, Vystorm.vystorm_core.compat.PassengerTeleport.class, "api.compat().passengers()"),
            new Part(Vystorm.vystorm_core.compat.JsonCompatInterface.class, Vystorm.vystorm_core.compat.JsonCompat.class, "api.compat().json()"),
            new Part(Vystorm.vystorm_core.compat.ClickActionsInterface.class, Vystorm.vystorm_core.compat.ClickActions.class, "api.compat().clicks()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.compat.ClientEventsInterface.class, Vystorm.vystorm_core.compat.ClientEvents.class, "api.compat().clientEvents()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.class, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.class, "api.data().files()"),
            new Part(Vystorm.vystorm_core.storage.VersionedStoreInterface.class, Vystorm.vystorm_core.storage.VersionedStore.class, "api.data().versioned()"),
            new Part(Vystorm.vystorm_core.storage.MigrationPreviewsInterface.class, Vystorm.vystorm_core.storage.MigrationPreviews.class, "api.data().migrationPreviews()"),
            new Part(Vystorm.vystorm_core.knownplayers.KnownPlayersInterface.class, Vystorm.vystorm_core.knownplayers.KnownPlayers.class, "api.data().knownPlayers()", "start", "shutdown"),
            new Part(Vystorm.vystorm_core.schedule.TaskGroupInterface.class, Vystorm.vystorm_core.schedule.TaskGroup.class, "api.tasks()"),
            new Part(Vystorm.vystorm_core.commands.CoreCommandsInterface.class, Vystorm.vystorm_core.commands.CoreCommands.class, "api.command()", "start", "configure", "serverLoaded"),
            new Part(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.class, Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.class, "api.settings().config()", "startMenu"),
            new Part(Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface.class, Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs.class, "api.settings().prefs()"),
            new Part(Vystorm.vystorm_core._uiMethods.chat.ChatActionsInterface.class, Vystorm.vystorm_core._uiMethods.chat.ChatActions.class, "api.message().chat()"),
            new Part(Vystorm.vystorm_core.chat.ChatChannelsInterface.class, Vystorm.vystorm_core.chat.ChatChannels.class, "api.message().channels()"),
            new Part(Vystorm.vystorm_core.inbox.InboxInterface.class, Vystorm.vystorm_core.inbox.Inbox.class, "api.message().inbox()"),
            new Part(Vystorm.vystorm_core.news.NewsInterface.class, Vystorm.vystorm_core.news.News.class, "api.message().news()"),
            new Part(Vystorm.vystorm_core._uiMethods.notifications.RoutineMessagesInterface.class, Vystorm.vystorm_core._uiMethods.notifications.RoutineMessages.class, "api.message().routine()", "start", "stop"),
            new Part(Vystorm.vystorm_core.panel.PanelsInterface.class, Vystorm.vystorm_core.panel.Panels.class, "api.gui().panels()"),
            new Part(Vystorm.vystorm_core.screen.ScreensInterface.class, Vystorm.vystorm_core.screen.Screens.class, "api.gui().screens()"),
            new Part(Vystorm.vystorm_core.screen.HudsInterface.class, Vystorm.vystorm_core.screen.Huds.class, "api.gui().huds()"),
            new Part(Vystorm.vystorm_core.playermenu.PlayerMenuInterface.class, Vystorm.vystorm_core.playermenu.PlayerMenu.class, "api.gui().playerMenu()"),
            new Part(Vystorm.vystorm_core.profile.ProfilesInterface.class, Vystorm.vystorm_core.profile.Profiles.class, "api.gui().profiles()"),
            new Part(Vystorm.vystorm_core._uiMethods.menu.MenuExplorerInterface.class, Vystorm.vystorm_core._uiMethods.menu.MenuExplorer.class, "api.gui().menuExplorer()"),
            new Part(Vystorm.vystorm_core.picker.PickersInterface.class, Vystorm.vystorm_core.picker.Pickers.class, "api.gui().pickers()", "start", "shutdown", "release"),
            new Part(Vystorm.vystorm_core.menus.FileMenusInterface.class, Vystorm.vystorm_core.menus.FileMenus.class, "api.gui().fileMenus()"),
            new Part(Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboardingInterface.class, Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboarding.class, "api.gui().onboarding()", "markMenu", "stop", "markLanguage", "register", "open", "reset", "skip"),
            new Part(Vystorm.vystorm_core._uiMethods.menu.CompatMenuButtonsInterface.class, Vystorm.vystorm_core._uiMethods.menu.CompatMenuButtons.class, "api.dialogs().buttons()"),
            new Part(Vystorm.vystorm_core.worlds.ManagedWorldsInterface.class, Vystorm.vystorm_core.worlds.ManagedWorlds.class, "api.worldGenerator().worlds()"),
            new Part(Vystorm.vystorm_core.visual.VisualsInterface.class, Vystorm.vystorm_core.visual.Visuals.class, "api.particle().visuals()", "shutdown", "lifecycleListener"),
            new Part(Vystorm.vystorm_core.visual.ParticlePathsInterface.class, Vystorm.vystorm_core.visual.ParticlePaths.class, "api.particle().paths()"),
            new Part(Vystorm.vystorm_core.packet.PacketViewInterface.class, Vystorm.vystorm_core.packet.PacketView.class, "api.packages().packetView()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.packet.ClientBiomesInterface.class, Vystorm.vystorm_core.packet.ClientBiomes.class, "api.packages().clientBiomes()", "shutdown", "initialize"),
            new Part(Vystorm.vystorm_core.packet.ClientChunksInterface.class, Vystorm.vystorm_core.packet.ClientChunks.class, "api.packages().clientChunks()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.packet.ClientBlocksInterface.class, Vystorm.vystorm_core.packet.ClientBlocks.class, "api.packages().clientBlocks()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.display.ClientDisplaysInterface.class, Vystorm.vystorm_core.display.ClientDisplays.class, "api.packages().clientDisplays()"),
            new Part(Vystorm.vystorm_core.lifecycle.UpdateSourcesInterface.class, Vystorm.vystorm_core.lifecycle.UpdateSources.class, "api.admin().updates()"),
            new Part(Vystorm.vystorm_core.lifecycle.FeatureDiagnosticsInterface.class, Vystorm.vystorm_core.lifecycle.FeatureDiagnostics.class, "api.admin().diagnostics()"),
            new Part(Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessagesInterface.class, Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.class, "api.debug().messages()"),
            new Part(Vystorm.vystorm_core.lifecycle.ProviderFailuresInterface.class, Vystorm.vystorm_core.lifecycle.ProviderFailures.class, "api.debug().failures()"),
            new Part(Vystorm.vystorm_core.pack.ResourcePacksInterface.class, Vystorm.vystorm_core.pack.ResourcePacks.class, "api.ressource().packs()", "initialize", "shutdown"),
            new Part(Vystorm.vystorm_core.worldevent.WorldEventServicesInterface.class, Vystorm.vystorm_core.worldevent.WorldEventServices.class, "api.events().services()"),
            new Part(Vystorm.vystorm_core.lang.CoreLanguageInterface.class, Vystorm.vystorm_core.lang.CoreLanguage.class, "api.lang().coreLanguage()", "enable", "registerSettings", "disable"),
            new Part(Vystorm.vystorm_core.progression.CharacterServicesInterface.class, Vystorm.vystorm_core.progression.CharacterServices.class, "api.player().character()"),
            new Part(Vystorm.vystorm_core.progression.DisciplineServicesInterface.class, Vystorm.vystorm_core.progression.DisciplineServices.class, "api.player().disciplines()"),
            new Part(Vystorm.vystorm_core.loot.LootTablesInterface.class, Vystorm.vystorm_core.loot.LootTables.class, "api.loot()"),
            new Part(Vystorm.vystorm_core.loot.LootRollInterface.class, Vystorm.vystorm_core.loot.LootRoll.class, "api.loot().rolls()"),
            new Part(Vystorm.vystorm_core.loot.MobLootInterface.class, Vystorm.vystorm_core.loot.MobLoot.class, "api.loot().mobs()", "configure"),
            new Part(Vystorm.vystorm_core.loot.LootLuckInterface.class, Vystorm.vystorm_core.loot.LootLuck.class, "api.loot().luck()"),
            new Part(Vystorm.vystorm_core.loot.LootTableConfigInterface.class, Vystorm.vystorm_core.loot.LootTableConfig.class, "api.loot().config()", "reloadCore"),
            new Part(Vystorm.vystorm_core.item.RewardPouchInterface.class, Vystorm.vystorm_core.item.RewardPouch.class, "api.loot().pouches()"),
            new Part(Vystorm.vystorm_core.progression.RewardEligibilityInterface.class, Vystorm.vystorm_core.progression.RewardEligibility.class, "api.loot().eligibility()"),
            new Part(Vystorm.vystorm_core.web.WebServicesInterface.class, Vystorm.vystorm_core.web.WebServices.class, "api.web()"),
            new Part(Vystorm.vystorm_core.web.ClientServicesInterface.class, Vystorm.vystorm_core.web.ClientServices.class, "api.web().client()"),
            new Part(Vystorm.vystorm_core.web.ClientUiInterface.class, Vystorm.vystorm_core.web.ClientUi.class, "api.web().clientUi()"),
            new Part(Vystorm.vystorm_core.party.PartyServicesInterface.class, Vystorm.vystorm_core.party.PartyServices.class, "api.parties()"),
            new Part(Vystorm.vystorm_core.survey.WorldSurveyInterface.class, Vystorm.vystorm_core.survey.WorldSurvey.class, "api.survey()", "configure", "shutdown", "runtime"),
            new Part(Vystorm.vystorm_core.item.ItemServicesInterface.class, Vystorm.vystorm_core.item.ItemServices.class, "api.items()"),
            new Part(Vystorm.vystorm_core.item.ItemRefsInterface.class, Vystorm.vystorm_core.item.ItemRefs.class, "api.items().refs()"),
            new Part(Vystorm.vystorm_core.item.ItemRestrictionsInterface.class, Vystorm.vystorm_core.item.ItemRestrictions.class, "api.items().restrictions()"),
            new Part(Vystorm.vystorm_core.mythic.MmoItemsBridgeInterface.class, Vystorm.vystorm_core.mythic.MmoItemsBridge.class, "api.items().mmoItems()"),
            new Part(Vystorm.vystorm_core.mob.MobServicesInterface.class, Vystorm.vystorm_core.mob.MobServices.class, "api.mobs()"),
            new Part(Vystorm.vystorm_core.mob.extension.MobExtensionsInterface.class, Vystorm.vystorm_core.mob.extension.MobExtensions.class, "api.mobs().extensions()"),
            new Part(Vystorm.vystorm_core.mob.MobRefsInterface.class, Vystorm.vystorm_core.mob.MobRefs.class, "api.mobs().refs()"),
            new Part(Vystorm.vystorm_core.mob.MobContextServicesInterface.class, Vystorm.vystorm_core.mob.MobContextServices.class, "api.mobs().context()"),
            new Part(Vystorm.vystorm_core.mob.RelationServicesInterface.class, Vystorm.vystorm_core.mob.RelationServices.class, "api.mobs().relations()"),
            new Part(Vystorm.vystorm_core.mythic.MythicMobsBridgeInterface.class, Vystorm.vystorm_core.mythic.MythicMobsBridge.class, "api.mobs().mythic()"),
            new Part(Vystorm.vystorm_core.combat.CombatRegistryInterface.class, Vystorm.vystorm_core.combat.CombatRegistry.class, "api.runtime().combat()", "clear"),
            new Part(Vystorm.vystorm_core.threat.ThreatServicesInterface.class, Vystorm.vystorm_core.threat.ThreatServices.class, "api.runtime().threat()", "purgeExpired"),
            new Part(Vystorm.vystorm_core.curve.CurveRegistryInterface.class, Vystorm.vystorm_core.curve.CurveRegistry.class, "api.runtime().curves()"));
}
