package Vystorm.vystorm_core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import Vystorm.vystorm_core._advancedMethods.areaHandler.AreaInterface;
import Vystorm.vystorm_core._advancedMethods.packageHandler.PackageInterface;
import Vystorm.vystorm_core._advancedMethods.particleHandler.ParticleInterface;
import Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler.WorldGeneratorInterface;
import Vystorm.vystorm_core._coreMethods.dataBaseHandler.DataBaseInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._coreMethods.dataStorage.DataStorageInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core._coreMethods.eventRegulator.PriorityInterface;
import Vystorm.vystorm_core._coreMethods.playerHandler.PlayerHandlerInterface;
import Vystorm.vystorm_core._coreMethods.ressourceLoader.RessourceInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.MessageInterface;
import Vystorm.vystorm_core._uiMethods.admin.AdminInterface;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.chat.ChatActionsInterface;
import Vystorm.vystorm_core._uiMethods.commands.CommandInterface;
import Vystorm.vystorm_core._uiMethods.menu.CompatMenuButtonsInterface;
import Vystorm.vystorm_core._uiMethods.menu.MenuExplorerInterface;
import Vystorm.vystorm_core._uiMethods.notifications.RoutineMessagesInterface;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface;
import Vystorm.vystorm_core._uiMethods.settings.SettingsInterface;
import Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboardingInterface;
import Vystorm.vystorm_core.chat.ChatChannelsInterface;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.commands.CoreCommandsInterface;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.display.ClientDisplaysInterface;
import Vystorm.vystorm_core.inbox.InboxInterface;
import Vystorm.vystorm_core.item.ItemsInterface;
import Vystorm.vystorm_core.lang.CoreLanguageInterface;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.lifecycle.FeatureDiagnosticsInterface;
import Vystorm.vystorm_core.lifecycle.ProviderFailuresInterface;
import Vystorm.vystorm_core.lifecycle.UpdateSourcesInterface;
import Vystorm.vystorm_core.loot.LootInterface;
import Vystorm.vystorm_core.mob.MobsInterface;
import Vystorm.vystorm_core.news.NewsInterface;
import Vystorm.vystorm_core.pack.ResourcePacksInterface;
import Vystorm.vystorm_core.packet.ClientBiomesInterface;
import Vystorm.vystorm_core.packet.ClientChunksInterface;
import Vystorm.vystorm_core.panel.PanelsInterface;
import Vystorm.vystorm_core.party.PartyServicesInterface;
import Vystorm.vystorm_core.playermenu.PlayerMenuInterface;
import Vystorm.vystorm_core.profile.ProfilesInterface;
import Vystorm.vystorm_core.progression.CharacterServicesInterface;
import Vystorm.vystorm_core.progression.DisciplineServicesInterface;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.runtime.RuntimeActionHub;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import Vystorm.vystorm_core.runtime.RuntimeInterface;
import Vystorm.vystorm_core.schedule.TaskGroupInterface;
import Vystorm.vystorm_core.screen.HudsInterface;
import Vystorm.vystorm_core.screen.ScreensInterface;
import Vystorm.vystorm_core.storage.MigrationPreviewsInterface;
import Vystorm.vystorm_core.storage.VersionedStoreInterface;
import Vystorm.vystorm_core.visual.ParticlePathsInterface;
import Vystorm.vystorm_core.visual.VisualsInterface;
import Vystorm.vystorm_core.web.WebInterface;
import Vystorm.vystorm_core.worldevent.WorldEventServicesInterface;
import Vystorm.vystorm_core.worldevent.WorldEvents;
import Vystorm.vystorm_core.worlds.ManagedWorldsInterface;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.MockedStatic;

/** The managers added to {@link API} in 0.64.0 and the parts folded into the managers that existed before. */
class ApiManagersTest {
    private record NewManager<T>(String name, Function<API, T> accessor, BiConsumer<API, T> override, Supplier<T> replacement) {
        void check(API api) {
            T first = accessor.apply(api);
            assertNotNull(first, name + "() has a default");
            assertSame(first, accessor.apply(api), name + "() is stable");
            T mine = replacement.get();
            assertNotSame(first, mine);
            override.accept(api, mine);
            assertSame(mine, accessor.apply(api), "override replaces " + name + "()");
            NullPointerException rejected = assertThrows(NullPointerException.class, () -> override.accept(api, null));
            assertEquals("replacement", rejected.getMessage());
            assertSame(mine, accessor.apply(api), "a rejected null keeps the manager");
        }
    }

    private static final List<NewManager<?>> NEW = List.of(
            new NewManager<>("compat", API::compat, API::overrideCompatHandler, () -> new CompatInterface() {}),
            new NewManager<>("tasks", API::tasks, API::overrideTaskHandler, () -> new TaskGroupInterface() {}),
            new NewManager<>("loot", API::loot, API::overrideLootHandler, () -> new LootInterface() {}),
            new NewManager<>("web", API::web, API::overrideWebHandler, () -> new WebInterface() {}),
            new NewManager<>("parties", API::parties, API::overridePartyHandler, () -> new PartyServicesInterface() {}),
            new NewManager<>("items", API::items, API::overrideItemHandler, () -> new ItemsInterface() {}),
            new NewManager<>("mobs", API::mobs, API::overrideMobHandler, () -> new MobsInterface() {}),
            new NewManager<>("runtime", API::runtime, API::overrideRuntimeHandler, () -> new RuntimeInterface() {}));

    @TestFactory
    Stream<DynamicTest> givenNewManager_whenAccessedAndOverridden_thenDefaultThenReplacementAndNullRejected() {
        return NEW.stream().map(manager -> dynamicTest("api." + manager.name() + "()", () -> {
            try (MockedStatic<main> core = mockStatic(main.class)) {
                manager.check(new API("test"));
            }
        }));
    }

    @Test
    void givenTwoApiInstances_whenOneIsOverridden_thenTheOtherKeepsItsDefault() {
        try (MockedStatic<main> core = mockStatic(main.class)) {
            API first = new API("a"), second = new API("b");
            CompatInterface mine = new CompatInterface() {};
            first.overrideCompatHandler(mine);
            assertSame(mine, first.compat());
            assertNotSame(mine, second.compat());
        }
    }

    @Test
    void givenReplacementThatOverridesOneGroup_whenUsed_thenOtherGroupsStayDefault() {
        PlatformCompatInterface platform = new PlatformCompatInterface() {};
        CompatInterface mine = new CompatInterface() {
            @Override public PlatformCompatInterface platform() { return platform; }
        };
        assertSame(platform, mine.platform());
        assertSame(new CompatInterface() {}.server(), mine.server());
    }

    @Test
    void givenNewManagers_whenPartsRequested_thenDefaultsAreHandedOut() {
        LootInterface loot = new LootInterface() {};
        assertNotNull(loot.rolls()); assertNotNull(loot.mobs()); assertNotNull(loot.luck());
        assertNotNull(loot.config()); assertNotNull(loot.pouches()); assertNotNull(loot.eligibility());
        WebInterface web = new WebInterface() {};
        assertNotNull(web.client()); assertNotNull(web.clientUi());
        ItemsInterface items = new ItemsInterface() {};
        assertNotNull(items.refs()); assertNotNull(items.restrictions()); assertNotNull(items.mmoItems());
        MobsInterface mobs = new MobsInterface() {};
        assertNotNull(mobs.extensions()); assertNotNull(mobs.refs()); assertNotNull(mobs.context());
        assertNotNull(mobs.relations()); assertNotNull(mobs.mythic());
        RuntimeInterface runtime = new RuntimeInterface() {};
        assertSame(RuntimeActionHub.get(), runtime.actions());
        assertSame(RuntimeContextHub.get(), runtime.context());
        assertNotNull(runtime.combat()); assertNotNull(runtime.threat()); assertNotNull(runtime.curves());
        CompatInterface compat = new CompatInterface() {};
        for (Object group : List.of(compat.platform(), compat.server(), compat.inventory(), compat.gameplay(), compat.world(),
                compat.storage(), compat.items(), compat.navigation(), compat.passengers(), compat.json(), compat.clicks(),
                compat.clientEvents())) assertNotNull(group);
    }

    /** A handler written before 0.64.0 implements only the old methods; it inherits the new parts. */
    private static <T> T legacy(Class<T> type) { return mock(type, CALLS_REAL_METHODS); }

    @Test
    void givenHandlerWrittenBefore064_whenPartsRequested_thenItInheritsTheDelegatingDefaults() {
        DataStorageInterface data = legacy(DataStorageInterface.class);
        assertSame(AtomicFilesInterface.DEFAULT, data.files());
        assertSame(VersionedStoreInterface.DEFAULT, data.versioned());
        assertSame(MigrationPreviewsInterface.DEFAULT, data.migrationPreviews());

        SettingsInterface settings = legacy(SettingsInterface.class);
        assertSame(ConfigEditorInterface.DEFAULT, settings.config());
        assertSame(PlayerPrefsInterface.DEFAULT, settings.prefs());

        MessageInterface message = legacy(MessageInterface.class);
        assertSame(ChatActionsInterface.DEFAULT, message.chat());
        assertSame(ChatChannelsInterface.DEFAULT, message.channels());
        assertSame(InboxInterface.DEFAULT, message.inbox());
        assertSame(NewsInterface.DEFAULT, message.news());
        assertSame(RoutineMessagesInterface.DEFAULT, message.routine());

        GuiInteface gui = legacy(GuiInteface.class);
        assertSame(PanelsInterface.DEFAULT, gui.panels());
        assertSame(ScreensInterface.DEFAULT, gui.screens());
        assertSame(HudsInterface.DEFAULT, gui.huds());
        assertSame(PlayerMenuInterface.DEFAULT, gui.playerMenu());
        assertSame(ProfilesInterface.DEFAULT, gui.profiles());
        assertSame(MenuExplorerInterface.DEFAULT, gui.menuExplorer());
        assertSame(CoreOnboardingInterface.DEFAULT, gui.onboarding());

        assertSame(CompatMenuButtonsInterface.DEFAULT, legacy(Vystorm.vystorm_core.compat.dialog.DialogInterface.class).buttons());
        assertSame(CompatMenuButtonsInterface.DEFAULT, legacy(Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class).buttons());

        WorldGeneratorInterface worlds = legacy(WorldGeneratorInterface.class);
        assertSame(ManagedWorldsInterface.DEFAULT, worlds.worlds());

        ParticleInterface particle = legacy(ParticleInterface.class);
        assertSame(VisualsInterface.DEFAULT, particle.visuals());
        assertSame(ParticlePathsInterface.DEFAULT, particle.paths());

        PackageInterface packages = legacy(PackageInterface.class);
        assertSame(ClientBiomesInterface.DEFAULT, packages.clientBiomes());
        assertSame(ClientChunksInterface.DEFAULT, packages.clientChunks());
        assertSame(ClientDisplaysInterface.DEFAULT, packages.clientDisplays());

        AdminInterface admin = legacy(AdminInterface.class);
        assertSame(UpdateSourcesInterface.DEFAULT, admin.updates());
        assertSame(FeatureDiagnosticsInterface.DEFAULT, admin.diagnostics());

        assertSame(ProviderFailuresInterface.DEFAULT, legacy(DebugInterface.class).failures());
        assertSame(ResourcePacksInterface.DEFAULT, legacy(RessourceInterface.class).packs());

        PlayerHandlerInterface player = legacy(PlayerHandlerInterface.class);
        assertSame(CharacterServicesInterface.DEFAULT, player.character());
        assertSame(DisciplineServicesInterface.DEFAULT, player.disciplines());

        assertSame(EventStages.shared(), legacy(PriorityInterface.class).stages());
        assertSame(RegionService.get(), legacy(AreaInterface.class).regions());
        assertNotNull(legacy(AreaInterface.class).spawnPoints());
        assertSame(WorldEventServicesInterface.DEFAULT, WorldEvents.get().services());
        assertSame(CoreLanguageInterface.DEFAULT, Localization.get().coreLanguage());
        assertNotNull(legacy(DataBaseInterface.class).sharedStatus());
        assertTrue(legacy(DataBaseInterface.class).sharedFailure().isEmpty());
    }

    @Test
    void givenCommandManager_whenTreeMethodsCalled_thenCoreCommandsReceivesThem() {
        CommandInterface command = legacy(CommandInterface.class);
        assertTrue(CoreCommandsInterface.class.isAssignableFrom(CommandInterface.class));
        try (MockedStatic<CoreCommands> statics = mockStatic(CoreCommands.class)) {
            command.registrations();
            command.find("rails");
            command.refreshAliases();
            statics.verify(CoreCommands::registrations);
            statics.verify(() -> CoreCommands.find("rails"));
            statics.verify(CoreCommands::refreshAliases);
        }
    }

    @Test
    void givenCapabilities_whenQueried_thenManagersAndEventStagesExistSince064() {
        assertTrue(CoreApi.has(CoreApi.Capability.MANAGERS));
        assertEquals("0.64.0", CoreApi.since("managers").orElseThrow());
        assertTrue(CoreApi.has(CoreApi.Capability.EVENT_STAGES));
        assertEquals("0.64.0", CoreApi.since("event-stages").orElseThrow());
    }
}
