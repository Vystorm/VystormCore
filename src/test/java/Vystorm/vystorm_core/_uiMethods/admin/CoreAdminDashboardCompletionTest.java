package Vystorm.vystorm_core._uiMethods.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/** {@code /vadmin}: every handled subcommand is suggested for admins and nothing without {@code vystorm_core.admin}. */
class CoreAdminDashboardCompletionTest {
    @Test void givenAdminOrNot_whenCompletingVadmin_thenAllSubcommandsOrNothing() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            JavaPlugin core = mock(JavaPlugin.class);
            when(core.getName()).thenReturn("vystorm_core");
            Server server = mock(Server.class);
            PluginManager manager = mock(PluginManager.class);
            when(core.getServer()).thenReturn(server);
            when(server.getPluginManager()).thenReturn(manager);
            Plugin skills = mock(Plugin.class);
            when(skills.getName()).thenReturn("VystormSkills");
            PluginDescriptionFile description = mock(PluginDescriptionFile.class);
            when(description.getDepend()).thenReturn(List.of("vystorm_core"));
            when(description.getSoftDepend()).thenReturn(List.of());
            when(description.getPermissions()).thenReturn(List.of());
            when(skills.getDescription()).thenReturn(description);
            when(manager.getPlugins()).thenReturn(new Plugin[] {core, skills});
            Player admin = mock(Player.class), member = mock(Player.class);
            when(admin.hasPermission("vystorm_core.admin")).thenReturn(true);
            when(admin.getName()).thenReturn("Admin");
            doReturn(List.of(admin)).when(server).getOnlinePlayers();
            AdminInterface registry = mock(AdminInterface.class);
            when(registry.modules()).thenReturn(List.of());
            when(registry.owner(anyString())).thenReturn(Optional.empty());
            CoreAdminDashboard dashboard = new CoreAdminDashboard(core, registry);

            List<String> all = dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {""});
            assertTrue(all.containsAll(CoreAdminDashboard.SUBCOMMANDS), all.toString());
            assertEquals(List.of(), dashboard.onTabComplete(member, mock(Command.class), "vadmin", new String[] {""}));
            assertEquals(List.of("reload", "audit", "export", "import"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"language", ""}));
            assertEquals(List.of("Admin"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"diagnostics", "a"}));
            assertEquals(List.of("vystorm_core", "VystormSkills"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"permissions", ""}));
            assertEquals(List.of("1"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"permissions", "vystormskills", ""}));
            assertEquals(List.of("config"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"CON"}));
            assertEquals(List.of("history", "undo"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"config", ""}).subList(0, 2));
            assertEquals(List.of("list", "write", "post", "edit", "delete"), dashboard.onTabComplete(admin, mock(Command.class), "vadmin", new String[] {"news", ""}));
        }
    }
}
