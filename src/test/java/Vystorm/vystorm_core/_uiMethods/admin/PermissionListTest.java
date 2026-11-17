package Vystorm.vystorm_core._uiMethods.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class PermissionListTest {
    private static Plugin plugin(String name, List<String> depend, List<String> soft, List<Permission> nodes) {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn(name);
        PluginDescriptionFile d = mock(PluginDescriptionFile.class);
        when(d.getDepend()).thenReturn(depend);
        when(d.getSoftDepend()).thenReturn(soft);
        when(d.getPermissions()).thenReturn(nodes);
        when(d.getVersion()).thenReturn("1.0");
        when(p.getDescription()).thenReturn(d);
        return p;
    }

    @Test void givenPlugins_whenListing_thenCoreAndItsDependentsWithDeclaredAndConfigNodes() {
        List<Permission> nodes = new ArrayList<>();
        for (int i = 0; i < 14; i++) nodes.add(new Permission("bounty.node" + i, "Knoten " + i, PermissionDefault.TRUE));
        Plugin core = plugin("vystorm_core", List.of(), List.of(), List.of(new Permission("vystorm_core.admin", "Admin", PermissionDefault.OP)));
        Plugin bounty = plugin("Bounty", List.of(), List.of("Vystorm_Core"), nodes);
        Plugin foreign = plugin("WorldEdit", List.of(), List.of(), List.of());
        PluginManager manager = mock(PluginManager.class);
        when(manager.getPlugins()).thenReturn(new Plugin[] {foreign, bounty, core});
        when(manager.getPermission("vystorm_core.config.bounty")).thenReturn(new Permission("vystorm_core.config.bounty", "Konfiguration", PermissionDefault.OP));

        assertEquals(List.of(core, bounty), PermissionList.plugins(manager, core));
        List<PermissionList.Node> list = PermissionList.nodes(bounty, manager);
        assertEquals(15, list.size());
        assertEquals("vystorm_core.config.bounty", list.getLast().name());

        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        PermissionList.send(sender, manager, core, "bounty", 1);
        // header + 12 lines + "next page" hint
        verify(sender, times(14)).sendMessage(any(net.kyori.adventure.text.Component.class));
        PermissionList.send(sender, manager, core, "worldedit", 1);
        verify(sender, times(15)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
