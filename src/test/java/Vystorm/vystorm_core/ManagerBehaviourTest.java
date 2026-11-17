package Vystorm.vystorm_core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.FungusPlan;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureGeneratorInterface;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.WorldTreePlan;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugInterface;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core.combat.CombatRegistry;
import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.item.ItemsInterface;
import Vystorm.vystorm_core.lifecycle.UpdateSources;
import Vystorm.vystorm_core.lifecycle.UpdateSourcesInterface;
import Vystorm.vystorm_core.mob.MobService;
import Vystorm.vystorm_core.mob.MobServices;
import Vystorm.vystorm_core.mob.MobsInterface;
import Vystorm.vystorm_core.progression.DisciplineServices;
import Vystorm.vystorm_core.progression.DisciplineServicesInterface;
import Vystorm.vystorm_core.progression.DisciplineSource;
import Vystorm.vystorm_core.runtime.RuntimeInterface;
import Vystorm.vystorm_core.web.WebInterface;
import Vystorm.vystorm_core.web.WebServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** The managers reach the real static code: calls that work without a server, made through the manager. */
class ManagerBehaviourTest {
    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    @Test
    void givenDataFiles_whenWrittenThroughManager_thenTheFileIsReadBack(@TempDir Path folder) throws Exception {
        AtomicFilesInterface files = AtomicFilesInterface.DEFAULT;
        Path file = folder.resolve("data.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("answer", 42);

        files.write(file, yaml);

        assertTrue(Files.exists(file));
        assertEquals(42, files.readYaml(file, Logger.getAnonymousLogger()).getInt("answer"));
        Path moved = files.moveAside(file);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(moved));
    }

    @Test
    void givenItemManager_whenResolverRegisteredThroughRefs_thenTheStaticRegistryKnowsIt() {
        ItemsInterface items = new ItemsInterface() {};
        Plugin owner = plugin("manager-items");

        items.refs().register(owner, "manager_test", (id, amount) -> Optional.empty());

        assertTrue(ItemRefs.namespaces().contains("manager_test"));
        assertTrue(items.refs().namespaces().contains("manager_test"));
        items.refs().unregisterAll(owner);
        assertFalse(ItemRefs.namespaces().contains("manager_test"));
        assertFalse(items.available(), "no item provider is registered in a unit test");
    }

    @Test
    void givenMobManager_whenProviderRegistered_thenBothEntriesSeeIt() {
        MobsInterface mobs = new MobsInterface() {};
        Plugin owner = plugin("manager-mobs");

        mobs.register(owner, mock(MobService.class));

        assertTrue(MobServices.available());
        assertTrue(mobs.available());
        assertEquals(Optional.of("manager-mobs"), mobs.providerName());
        mobs.unregister(owner);
        assertFalse(MobServices.available());
        assertEquals("zombie", mobs.refs().id("mythic:zombie"));
    }

    @Test
    void givenRuntimeManager_whenCombatPolicyRegistered_thenTheStaticRegistryListsIt() {
        RuntimeInterface runtime = new RuntimeInterface() {};
        Plugin owner = plugin("manager-runtime");

        runtime.combat().register(owner, new CombatRegistry.Policy() {
            public String id() { return "manager-test"; }
            public int priority() { return 0; }
            public CombatRegistry.Decision evaluate(CombatRegistry.Context context) { return CombatRegistry.Decision.PASS; }
        });

        assertTrue(CombatRegistry.policies().contains("manager-test"));
        assertTrue(runtime.combat().policies().contains("manager-test"));
        runtime.combat().unregisterAll(owner);
        assertFalse(CombatRegistry.policies().contains("manager-test"));
        assertNotNull(runtime.threat());
        assertEquals(7.5, runtime.curves().apply("manager-test-missing", 1, 7.5));
    }

    @Test
    void givenAdminManager_whenUpdateSourceRegistered_thenTheStaticRegistryKnowsThePlugin() {
        UpdateSourcesInterface updates = UpdateSourcesInterface.DEFAULT;
        Plugin owner = plugin("manager-updates");

        updates.spigot(owner, 12345);

        assertTrue(UpdateSources.registered(owner));
        assertEquals(12345, updates.all().get("manager-updates"));
        updates.unregister(owner);
        assertFalse(UpdateSources.registered(owner));
    }

    @Test
    void givenPlayerManager_whenDisciplineSourceRegistered_thenItIsAvailableOnBothEntries() {
        DisciplineServicesInterface disciplines = DisciplineServicesInterface.DEFAULT;
        Plugin owner = plugin("manager-disciplines");

        disciplines.register(owner, mock(DisciplineSource.class));

        assertTrue(DisciplineServices.available());
        disciplines.unregister(owner);
        assertFalse(disciplines.available());
    }

    @Test
    void givenWebAndSettingsManagers_whenPureHelpersCalled_thenResultsEqualTheStaticOnes() {
        WebInterface web = new WebInterface() {};
        assertEquals(WebServices.overlayPage("shop", "cart"), web.overlayPage("shop", "cart"));
        assertEquals(WebServices.coreModuleId(), web.coreModuleId());
        assertFalse(web.available(), "the web platform is not started in a unit test");
        assertTrue(ConfigEditorInterface.DEFAULT.secret("database.shared.password"));
        assertFalse(ConfigEditorInterface.DEFAULT.secret("menu.title"));
    }

    @Test
    void givenStructureManager_whenPlanQuestionsAsked_thenAnswersEqualThePlans() {
        StructureGeneratorInterface structures = mock(StructureGeneratorInterface.class, CALLS_REAL_METHODS);
        for (Material plant : new Material[] {Material.OAK_SAPLING, Material.CRIMSON_FUNGUS, Material.STONE}) {
            assertEquals(FungusPlan.supports(plant), structures.supportsFungus(plant));
            assertEquals(WorldTreePlan.supports(plant), structures.supportsWorldTree(plant));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void givenDebugManager_whenShortMessageWritten_thenTheInstalledDebuggerGetsItWithTheTime() {
        DebugInterface debugger = mock(DebugInterface.class, CALLS_REAL_METHODS);
        API api = mock(API.class);
        when(api.debug()).thenReturn(debugger);
        try (MockedStatic<main> core = mockStatic(main.class)) {
            core.when(main::getApi).thenReturn(api);

            debugger.mid("[Test] through the manager");
            debugger.messages().high("[Test] failed", new IllegalStateException("boom"));

            verify(debugger).mid(eq("[Test] through the manager"), any(LocalDateTime.class));
            verify(debugger).high(org.mockito.ArgumentMatchers.contains("IllegalStateException: boom"), any(LocalDateTime.class));
        }
    }

    @Test
    void givenNoCore_whenShortMessageWritten_thenNothingIsThrown() {
        DebugInterface debugger = mock(DebugInterface.class, CALLS_REAL_METHODS);
        try (MockedStatic<main> core = mockStatic(main.class)) {
            assertDoesNotThrow(() -> debugger.low("[Test] before Core is enabled"));
        }
    }
}
