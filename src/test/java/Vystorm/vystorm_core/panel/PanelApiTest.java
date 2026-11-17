package Vystorm.vystorm_core.panel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.panel.internal.PanelWriter;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Final Review Important 10: öffentliche Panel-API vor dem Einfrieren – nichts Internes, getypte Builder, Threads. */
class PanelApiTest {
    static boolean has(Class<?> type, String method) { return Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(method)); }

    @Test
    void givenApi_whenInspected_thenUiAccessIsNotPublic() throws Exception {
        Class<?> access = Class.forName("Vystorm.vystorm_core.panel.UiAccess");
        assertFalse(Modifier.isPublic(access.getModifiers()), "UiAccess is Core-internal");
    }

    /** Re-Review Minor 8: niemand außerhalb von Core kann die Brücke ersetzen oder abfragen. */
    @Test
    void givenUiBridge_whenInspected_thenNoPublicInstallOrGet() throws Exception {
        Class<?> bridge = Class.forName("Vystorm.vystorm_core.panel.internal.UiBridge");
        assertTrue(Arrays.stream(bridge.getMethods()).noneMatch(m -> Modifier.isStatic(m.getModifiers())), "no public static install/get");
        assertEquals(0, bridge.getClasses().length, "no public holder class");
        assertFalse(Modifier.isPublic(Class.forName("Vystorm.vystorm_core.panel.internal.UiBridges").getModifiers()));
    }

    @Test
    void givenDisplayFactories_whenInspected_thenTypedBuildersWithoutForeignFields() throws Exception {
        assertEquals("Heading", Ui.class.getMethod("heading", Object.class).getReturnType().getSimpleName());
        assertFalse(has(Ui.class.getMethod("heading", Object.class).getReturnType(), "segments"), "heading(..).segments() must not compile");
        assertFalse(has(Ui.class.getMethod("text", Object.class).getReturnType(), "bar"));
        assertTrue(has(Ui.class.getMethod("progress", double.class, double.class).getReturnType(), "segments"));
        assertTrue(has(Ui.class.getMethod("timer", long.class, long.class).getReturnType(), "bar"));
        assertFalse(Arrays.stream(Ui.class.getClasses()).anyMatch(c -> c.getSimpleName().equals("Simple")), "no catch-all builder");
    }

    @Test
    void givenNumberFactory_whenInspected_thenOwnTypeWithoutSliderOnlyMethods() throws Exception {
        Class<?> number = Ui.class.getMethod("number", String.class, Object.class).getReturnType();
        assertNotEquals(Ui.Slider.class, number);
        assertFalse(has(number, "format"));
        assertFalse(has(number, "live"));
        assertTrue(has(number, "decimals"));
    }

    @Test
    void givenTableAndBox_whenBuilt_thenUnambiguousWidthAndEnumJustify() throws Exception {
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        Method justify = Ui.Box.class.getMethod("justify", Ui.Justify.class);
        assertNotNull(justify);
        assertFalse(Arrays.stream(Ui.Box.class.getMethods()).anyMatch(m -> m.getName().equals("justify") && m.getParameterTypes()[0] == String.class));
        Panel page = Panel.page("T").children(
                Ui.table("t").column("a", "A").columnWidth(48).column("b", "B").width(200),
                Ui.row().justify(Ui.Justify.BETWEEN).children(Ui.text("x")));
        PanelDocument d = PanelSchema.document(PanelWriter.write("a:b", page, viewer, null, null).document(), Deadline.NONE);
        PanelNode.Table t = (PanelNode.Table) d.find("t");
        assertEquals(48, t.columns().getFirst().width().value(), "columnWidth sets the last column");
        assertEquals(200, t.common().width().value(), "width sets the element width");
        assertEquals(PanelNode.Justify.BETWEEN, ((PanelNode.Box) d.root().children().get(1)).justify());
    }

    @Test
    void givenOtherThread_whenRefreshCalled_thenHoppedToServerThread() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("ApiThread");
        when(plugin.isEnabled()).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            Panels panels = Panels.of(plugin);
            Player p = mock(Player.class);
            assertDoesNotThrow(() -> panels.refresh(p, "x"));
            assertDoesNotThrow(() -> panels.refreshAll("x"));
            assertDoesNotThrow(() -> panels.close(p, "x"));
            verify(scheduler, times(3)).runTask(eq(plugin), any(Runnable.class));
            assertThrows(IllegalStateException.class, () -> panels.open(p, "x"), "open returns a result: must be called on the server thread");
        } finally {
            Panels.release(plugin);
        }
    }

    /** Minor: Panels.unregister nimmt auch den /vmenu-Eintrag aus register(..menu(..)) wieder heraus. */
    @Test
    void givenPanelWithMenuEntry_whenUnregistered_thenMenuEntryRemoved() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("ApiMenu");
        when(plugin.isEnabled()).thenReturn(true);
        Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface gui = mock(Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface.class);
        Vystorm.vystorm_core.API api = mock(Vystorm.vystorm_core.API.class);
        when(api.gui()).thenReturn(gui);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class); MockedStatic<Vystorm.vystorm_core.main> main = mockStatic(Vystorm.vystorm_core.main.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            main.when(Vystorm.vystorm_core.main::getApi).thenReturn(api);
            Panels panels = Panels.of(plugin);
            panels.register("bank", PanelSpec.builder("Bank").render(c -> Panel.page("Bank"))
                    .menu(Vystorm.vystorm_core._uiMethods.menu.MenuCategory.ECONOMY, org.bukkit.Material.GOLD_INGOT, "").build());
            panels.unregister("bank");
            verify(gui).unregisterMenu(plugin, "bank");
        } finally {
            Panels.release(plugin);
        }
    }
}
