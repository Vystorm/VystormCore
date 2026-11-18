package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import Vystorm.vystorm_core.main;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** The manager on the hub: default, replacement, lifecycle cleanup and the capability. */
class PlaceholderManagerTest {
    private final Plugin plugin = PlaceholderTesting.plugin("ManagerTestPlugin");

    @AfterEach
    void cleanUp() {
        PlaceholderHandler.shared().unregisterAll(plugin);
        PlaceholderHandler.shared().route(null);
    }

    @Test
    void givenHub_whenPlaceholdersRequested_thenCoresRegistryAndAReplacementAfterOverride() {
        try (MockedStatic<main> core = mockStatic(main.class)) {
            API api = new API("test");
            assertSame(PlaceholderHandler.shared(), api.placeholders());
            PlaceholderInterface mine = new PlaceholderInterface() {
                @Override public String resolve(OfflinePlayer viewer, Player other, String text) { return "mine"; }
            };
            api.overridePlaceholderHandler(mine);
            assertSame(mine, api.placeholders());
            assertEquals("mine", api.placeholders().resolve(null, "anything"));
            assertEquals("mine", api.placeholders().resolveFor(null, "anything"));
            assertNotSame(mine, new API("other").placeholders());
        }
    }

    @Test
    void givenReplacementThatOverridesNothing_whenUsed_thenCoresRegistryAnswers() {
        PlaceholderInterface replacement = new PlaceholderInterface() {};
        replacement.register(plugin, "managertest").global("answer", "", () -> 42);
        assertEquals("42 42", replacement.resolve(null, "{managertest:answer} %vystorm_managertest_answer%"));
        assertEquals("42", PlaceholderHandler.shared().value(null, "managertest", "answer"));
        assertEquals("42", replacement.request(null, null, "managertest_answer"));
        assertEquals("42", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(replacement.component(null, "{managertest:answer}")));
        assertTrue(replacement.namespaces().stream().anyMatch(namespace -> namespace.name().equals("managertest")));
        assertTrue(replacement.namespace("managertest").isPresent());
        assertTrue(replacement.unregister(plugin, "managertest"));
        assertFalse(replacement.namespace("managertest").isPresent());
    }

    @Test
    void givenPluginDisabled_whenCoreLifecycleReleasesIt_thenItsNamespaceIsGoneFromCoresRegistry() {
        PlaceholderNamespace namespace = PlaceholderHandler.shared().register(plugin, "managertest").global("answer", "", () -> 42);
        assertTrue(namespace.registered());
        CoreLifecycle.releaseAll(plugin);
        assertFalse(namespace.registered());
        assertEquals("{managertest:answer}", PlaceholderHandler.shared().resolve(null, null, "{managertest:answer}"));
    }

    @Test
    void givenReplacedManagerOnTheHub_whenAPluginIsReleased_thenTheReplacementIsToldToo() {
        List<Plugin> released = new ArrayList<>();
        PlaceholderInterface mine = new PlaceholderInterface() {
            @Override public int unregisterAll(Plugin owner) { released.add(owner); return 0; }
        };
        try (MockedStatic<main> core = mockStatic(main.class)) {
            API api = new API("test");
            api.overridePlaceholderHandler(mine);
            try { api.releaseAdvancedResources(plugin); }
            catch (RuntimeException otherManagersNeedAServer) { /* the placeholder manager is released first */ }
        }
        assertEquals(List.of(plugin), released);
    }

    @Test
    void givenCapabilities_whenQueried_thenPlaceholdersExistSince065() {
        assertTrue(CoreApi.has(CoreApi.Capability.PLACEHOLDERS));
        assertEquals("placeholders", CoreApi.Capability.PLACEHOLDERS);
        assertEquals("0.65.0", CoreApi.since("placeholders").orElseThrow());
        assertTrue(CoreApi.atLeast("0.65.0"));
    }
}
