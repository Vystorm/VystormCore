package Vystorm.vystorm_core._uiMethods.admin;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Method;

class CoreAdminDashboardTest {
    @Test void catalogUsesVerifiedWorldForgeSyntaxAndFactionsTargets() {
        AdminModule forge=Catalog.modules().stream().filter(m->m.id().equals("worldforge")).findFirst().orElseThrow();
        assertEquals("Vystorm-WorldForge",forge.plugin());
        assertEquals("<fraktion|thema> [module]",forge.actions().stream().filter(a->a.id().equals("town")).findFirst().orElseThrow().argumentHint());
        assertEquals("<thema> <typ> <anzahl> [fraktion]",forge.actions().stream().filter(a->a.id().equals("plan")).findFirst().orElseThrow().argumentHint());
        assertTrue(forge.actions().stream().noneMatch(a->a.id().equals("forget")));
        AdminModule factions=Catalog.modules().stream().filter(m->m.id().equals("factions")).findFirst().orElseThrow();
        assertEquals("Vystorm-Factions",factions.plugin());
        assertTrue(factions.actions().stream().anyMatch(a->a.id().equals("map")&&a.argumentHint().isBlank()));
        assertTrue(factions.actions().stream().anyMatch(a->a.id().equals("region")&&a.mutating()));
    }
    @Test void argumentInputRejectsInjectionAndBounds() { assertFalse(CoreAdminDashboard.safe("/op me"));assertFalse(CoreAdminDashboard.safe(" /op me"));assertFalse(CoreAdminDashboard.safe("one\ntwo"));assertFalse(CoreAdminDashboard.safe("x".repeat(193)));assertTrue(CoreAdminDashboard.safe("Steve 500")); }
    @Test void registryRejectsCrossOwnerAndClears() { Plugin one=mock(Plugin.class),two=mock(Plugin.class);when(one.getName()).thenReturn("One");when(two.getName()).thenReturn("Two");AdminRegistry r=new AdminRegistry();r.register(one,new AdminModule("same","x","One","one","p","",java.util.List.of()));assertThrows(IllegalArgumentException.class,()->r.register(two,new AdminModule("same","x","Two","two","p","",java.util.List.of())));r.clear();assertTrue(r.modules().isEmpty()); }
    @Test void executionUsesOriginalPlayerAndRejectsWrongOwnerAndDisabledOwner() throws Exception {
        JavaPlugin core=mock(JavaPlugin.class),owner=mock(JavaPlugin.class);when(core.isEnabled()).thenReturn(true);when(owner.isEnabled()).thenReturn(true);when(owner.getName()).thenReturn("Owner");Player p=mock(Player.class);when(p.isOnline()).thenReturn(true);when(p.hasPermission(anyString())).thenReturn(true);PluginCommand pc=mock(PluginCommand.class);when(owner.getCommand("owned")).thenReturn(pc);when(pc.getPlugin()).thenReturn(owner);
        AdminModule m=new AdminModule("owned","Owned","Owner","owned","module.admin","",java.util.List.of());AdminAction a=new AdminAction("go","Go","action.admin","fixed","","",false);AdminRegistry r=new AdminRegistry();r.register(owner,m);CoreAdminDashboard d=new CoreAdminDashboard(core,r);Method run=CoreAdminDashboard.class.getDeclaredMethod("execute",Player.class,AdminModule.class,AdminAction.class,String.class);run.setAccessible(true);run.invoke(d,p,m,a,"value");verify(pc).execute(p,"owned",new String[]{"fixed","value"});reset(pc);when(owner.getCommand("owned")).thenReturn(pc);when(pc.getPlugin()).thenReturn(mock(Plugin.class));run.invoke(d,p,m,a,"value");verify(pc,never()).execute(any(),any(),any());when(owner.isEnabled()).thenReturn(false);run.invoke(d,p,m,a,"value");verify(pc,never()).execute(any(),any(),any());
    }
    @Test void consoleCannotOpenModule() { JavaPlugin core=mock(JavaPlugin.class);when(core.isEnabled()).thenReturn(true);CommandSender console=mock(CommandSender.class);when(console.hasPermission("vystorm_core.admin")).thenReturn(true);CoreAdminDashboard d=new CoreAdminDashboard(core,new AdminRegistry());assertTrue(d.onCommand(console,mock(Command.class),"vadmin",new String[0]));ArgumentCaptor<net.kyori.adventure.text.Component> sent=ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);verify(console).sendMessage(sent.capture());assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(sent.getValue()).contains("/vadmin")); }
}
