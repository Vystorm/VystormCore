package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.autoGui.GuiInteface;
import Vystorm.vystorm_core._uiMethods.autoGui.MenuEntryView;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;

class MenuPanelsTest {
    GuiInteface gui;
    Player player;

    @BeforeEach void setUp() {
        PanelRegistry.clear();
        gui = mock(GuiInteface.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(gui.menuEntries(player, "")).thenReturn(List.of(
                new MenuEntryView("vystorm_core:settings", "Einstellungen", Material.COMPARATOR, "", "Alles einstellen"),
                new MenuEntryView("vystorm_core:category-economy", "Wirtschaft", Material.EMERALD, "", "")));
        when(gui.menuEntries(player, MenuCategory.ECONOMY.group())).thenReturn(List.of(
                new MenuEntryView("betterportals:mine", "Meine Portale", Material.OBSIDIAN, MenuCategory.ECONOMY.group(), "Portale"),
                new MenuEntryView("xpvault:bank", "Bank", Material.GOLD_INGOT, MenuCategory.ECONOMY.group(), "")));
        when(gui.openMenu(player, "xpvault:bank")).thenReturn(true);
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        PanelRegistry.register(owner, "betterportals:mine", PanelSpec.builder("Portale").render(c -> Panel.page("x")).build());
    }

    @AfterEach void tearDown() { PanelRegistry.clear(); }

    Vystorm.vystorm_core.panel.PanelAction action() {
        return new Vystorm.vystorm_core.panel.PanelAction() {
            @Override public Player player() { return player; }
            @Override public Object value() { return Boolean.TRUE; }
            @Override public PanelSession session() { return null; }
            @Override public void refresh() { }
        };
    }

    PanelContext ctx() {
        return new PanelContext() {
            @Override public Player player() { return player; }
            @Override public String arg() { return ""; }
            @Override public String text(String key, Object... p) { return key; }
            @Override public Component component(String key, Object... p) { return Component.text(key); }
            @Override public PanelSession session() { return null; }
        };
    }

    @Test void givenEntries_whenRendered_thenGridPerCategoryWithNativeAndClassicTargets() {
        PanelWriter.Written w = PanelWriter.write("vystorm_core:menu", MenuPanels.render(ctx(), gui), player, null, null);
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        List<PanelNode.Button> buttons = d.byId().values().stream().filter(n -> n instanceof PanelNode.Button).map(n -> (PanelNode.Button) n).toList();
        assertEquals(4, buttons.size(), "explorer + settings + 2 economy entries; category itself is not a tile");
        PanelNode.Button portals = buttons.stream().filter(b -> b.label().plainText().equals("Meine Portale")).findFirst().orElseThrow();
        assertInstanceOf(PanelNode.Button.Act.class, portals.target(), "use the registered opener so access is checked and visits recorded");
        PanelNode.Button bank = buttons.stream().filter(b -> b.label().plainText().equals("Bank")).findFirst().orElseThrow();
        assertInstanceOf(PanelNode.Button.Act.class, bank.target());
        assertTrue(w.handlers().get(bank.id()).handle(action(), Boolean.TRUE).success());
        verify(gui).openMenu(player, "xpvault:bank");
    }

    @Test void givenMenuGone_whenClicked_thenErrorResult() {
        when(gui.openMenu(player, "xpvault:bank")).thenReturn(false);
        PanelWriter.Written w = PanelWriter.write("vystorm_core:menu", MenuPanels.render(ctx(), gui), player, null, null);
        PanelDocument d = PanelSchema.document(w.document(), Deadline.NONE);
        String bank = d.byId().values().stream().filter(n -> n instanceof PanelNode.Button b && b.label().plainText().equals("Bank")).findFirst().orElseThrow().id();
        assertFalse(w.handlers().get(bank).handle(action(), Boolean.TRUE).success());
    }

    /** Final Review Important 7: Kachel-IDs hängen am Menüschlüssel, nicht an der Position. */
    @Test void givenEntryAddedBefore_whenRendered_thenTileIdsStable() {
        PanelDocument before = PanelSchema.document(PanelWriter.write("vystorm_core:menu", MenuPanels.render(ctx(), gui), player, null, null).document(), Deadline.NONE);
        String bank = before.byId().values().stream().filter(n -> n instanceof PanelNode.Button b && b.label().plainText().equals("Bank")).findFirst().orElseThrow().id();
        when(gui.menuEntries(player, MenuCategory.ECONOMY.group())).thenReturn(List.of(
                new MenuEntryView("aaa:new", "Neu", Material.STONE, MenuCategory.ECONOMY.group(), ""),
                new MenuEntryView("betterportals:mine", "Meine Portale", Material.OBSIDIAN, MenuCategory.ECONOMY.group(), "Portale"),
                new MenuEntryView("xpvault:bank", "Bank", Material.GOLD_INGOT, MenuCategory.ECONOMY.group(), "")));
        PanelDocument after = PanelSchema.document(PanelWriter.write("vystorm_core:menu", MenuPanels.render(ctx(), gui), player, null, null).document(), Deadline.NONE);
        assertEquals("Bank", ((PanelNode.Button) after.find(bank)).label().plainText(), "same id, same tile");
        assertEquals("m:xpvault:bank", bank);
    }
}
