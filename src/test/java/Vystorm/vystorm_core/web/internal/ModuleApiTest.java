package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import Vystorm.vystorm_core.web.WebMethod;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import Vystorm.vystorm_core.web.WebServices;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ModuleApiTest {
    private static final Vystorm.vystorm_core.web.WebHandler OK = r -> WebResponse.json(Map.of());

    @Test
    void givenInvalidIdsPathsAndPermissions_whenBuilt_thenRejected() {
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("Skills", "x"));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("a/b", "x"));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("a".repeat(33), "x"));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("ok", " "));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.get("x", OK));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.get("/a/../b", OK));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.get("/a//b", OK));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.get("/{a}/{a}", OK));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.get("/x", OK).mutating(true));
        assertThrows(IllegalArgumentException.class, () -> WebRoute.post("/x", OK).permission("bad node!"));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("m", "M").assets("../other/"));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("m", "M").asset("../x.js", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> WebModule.builder("m", "M")
                .route(WebRoute.get("/{a}", OK).build()).route(WebRoute.get("/{b}", OK).build()).build(), "same shape");
    }

    @Test
    void givenRouteDefaults_whenBuilt_thenPostIsMutatingAndGetHasNoBody() {
        WebRoute post = WebRoute.post("/a/{id}", OK).build();
        assertTrue(post.mutating());
        assertEquals(WebRoute.DEFAULT_MAX_BODY, post.maxBodyBytes());
        assertEquals(List.of("a", "{id}"), post.segments());
        WebRoute get = WebRoute.get("/", OK).maxBody(5000).build();
        assertFalse(get.mutating());
        assertEquals(0, get.maxBodyBytes());
        assertEquals("/", get.path());
        assertEquals(WebMethod.GET, get.method());
    }

    @Test
    void givenModuleIdOfOtherActivePlugin_whenRegistered_thenRejectedButSameOwnerReplaces() {
        ModuleRegistry registry = new ModuleRegistry();
        Plugin a = WebFixture.plugin("A"), b = WebFixture.plugin("B"), core = WebFixture.plugin("Core");
        registry.corePlugin(core);
        registry.register(a, WebModule.builder("shared", "A").build());
        assertThrows(IllegalStateException.class, () -> registry.register(b, WebModule.builder("shared", "B").build()));
        registry.register(a, WebModule.builder("shared", "A2").build());
        assertEquals("A2", registry.get("shared").orElseThrow().module().title());
        assertThrows(IllegalStateException.class, () -> registry.register(a, WebModule.builder("core", "Fake").build()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(a, WebModule.builder("logout", "X").build()));
        long rev = registry.revision();
        assertTrue(registry.unregister(a, "shared"));
        assertFalse(registry.unregister(a, "shared"));
        assertTrue(registry.revision() > rev);
    }

    @Test
    void givenDisabledOwner_whenQueried_thenModuleHiddenAndIdFree() {
        ModuleRegistry registry = new ModuleRegistry();
        Plugin a = WebFixture.plugin("A");
        registry.register(a, WebModule.builder("m", "M").build());
        org.mockito.Mockito.when(a.isEnabled()).thenReturn(false);
        assertTrue(registry.get("m").isEmpty());
        registry.register(WebFixture.plugin("B"), WebModule.builder("m", "M").build());
        assertTrue(registry.get("m").isPresent());
    }

    @Test
    void givenPluginDisable_whenCoreLifecycleReleases_thenWebModulesUnregistered() {
        Plugin owner = WebFixture.plugin("Skills");
        WebServices.register(owner, WebModule.builder("lifecycle-test", "T").route(WebRoute.get("/x", OK).build()).build());
        assertTrue(WebServices.registered("lifecycle-test"));
        assertFalse(WebServices.available(), "platform not started in tests");
        CoreLifecycle.releaseAll(owner);
        assertFalse(WebServices.registered("lifecycle-test"));
        assertFalse(WebServices.modules().contains("lifecycle-test"));
    }

    @Test
    void givenCoreApi_whenQueried_thenWebCapabilityAvailableSince0170() {
        assertTrue(Vystorm.vystorm_core.CoreApi.has(Vystorm.vystorm_core.CoreApi.Capability.WEB));
        assertEquals("0.17.0", Vystorm.vystorm_core.CoreApi.since("web").orElseThrow());
    }

    @Test
    void givenWebLogin_whenPrinted_thenTokenHidden() {
        var login = new Vystorm.vystorm_core.web.WebLogin("t".repeat(43), "https://w.example", "skills", java.time.Instant.EPOCH);
        assertEquals("https://w.example/login#" + "t".repeat(43), login.url());
        assertFalse(login.toString().contains("ttttt"));
    }
}
