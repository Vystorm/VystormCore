package Vystorm.vystorm_core.panel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.panel.internal.PanelWriter;
import Vystorm.vystorm_core.web.internal.clientproto.panel.ActionCheck;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Deadline;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelDocument;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelNode;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelSchema;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelsTest {
    Player player;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    PanelDocument check(PanelWriter.Written w) { return PanelSchema.document(w.document(), Deadline.NONE); }

    record Portal(String id, String name, int uses) {}

    @Test
    void givenSpecExample1_whenWritten_thenClientSchemaAccepts() {
        AtomicReference<Object> seen = new AtomicReference<>();
        Panel p = Panel.page("Einstellungen").size(Panel.Size.MEDIUM).children(
                Ui.section("Anzeige").children(
                        Ui.toggle("hud", "HUD").value(true).onChange((a, on) -> { seen.set(on); return Result.ok(); }),
                        Ui.slider("range", "Reichweite").range(4, 64, 4).value(16).format("{v} Blöcke")
                                .onChange((a, v) -> v > 32 ? Result.error("zu weit") : Result.ok()),
                        Ui.select("mode", "Modus").option("compact", "Kompakt").option("full", "Voll").value("full").segmented()
                                .onChange((a, id) -> Result.ok()),
                        Ui.input("title", "Name").maxLength(32).chars(Ui.Chars.NAME).value("Esoren").onChange((a, s) -> Result.ok())));
        PanelWriter.Written w = PanelWriter.write("example:display", p, player, null, null);
        PanelDocument d = check(w);
        assertInstanceOf(PanelNode.Toggle.class, d.find("hud"));
        assertEquals(4, w.handlers().size());
        Object value = ActionCheck.check(d, "hud", PanelLimits.KIND_CHANGE, "false");
        assertTrue(w.handlers().get("hud").handle(null, value).success());
        assertEquals(Boolean.FALSE, seen.get());
        assertFalse(w.handlers().get("range").handle(null, 40.0).success());
    }

    @Test
    void givenSpecExample2_whenWritten_thenCellButtonsArePrefixedAndClosuresKeepIds() {
        List<Portal> mine = List.of(new Portal("p17", "Nordtor", 1204), new Portal("p20", "Südtor", 3));
        AtomicReference<String> removed = new AtomicReference<>();
        Panel p = Panel.page("Meine Portale").size(Panel.Size.LARGE).children(
                Ui.table("list").column("name", "Name").sortable().column("uses", "Nutzungen").alignEnd().sortable().column("act", "").columnWidth(48)
                        .rows(mine, g -> Ui.row(g.id()).cell(g.name()).cell(Ui.cell(String.valueOf(g.uses())).sort(g.uses()))
                                .cell(Ui.buttons(
                                        Ui.button("rename").icon(Ui.item(Material.NAME_TAG)).tooltip("Umbenennen").navigate("rename", g.id()),
                                        Ui.button("rm").icon(Ui.item(Material.BARRIER)).danger().confirm("Entfernen?", g.name() + " wird gelöscht.")
                                                .onClick(a -> { removed.set(g.id()); return Result.ok(); }))))
                        .empty("Du hast noch keine Portale."));
        PanelWriter.Written w = PanelWriter.write("betterportals:mine", p, player, "portals", "");
        PanelDocument d = check(w);
        assertNotNull(d.find("list/p17/rm"));
        assertNotNull(d.find("list/p20/rm"));
        assertEquals("betterportals:rename", ((PanelNode.Button.Navigate) ((PanelNode.Button) d.find("list/p20/rename")).target()).panel());
        w.handlers().get("list/p20/rm").handle(null, Boolean.TRUE);
        assertEquals("p20", removed.get());
        assertEquals("portals", d.root().web().module());
    }

    @Test
    void givenNamelessElements_whenWritten_thenPathIdsAndDuplicatesRejected() {
        PanelWriter.Written w = PanelWriter.write("a:b", Panel.page("T").children(Ui.text("x"), Ui.col().children(Ui.text("y"))), player, null, null);
        PanelDocument d = check(w);
        assertNotNull(d.find("~0"));
        assertNotNull(d.find("~1/0"));
        assertThrows(IllegalStateException.class, () -> PanelWriter.write("a:b",
                Panel.page("T").children(Ui.text("x").id("dup"), Ui.text("y").id("dup")), player, null, null));
        assertThrows(IllegalArgumentException.class, () -> Ui.toggle("Bad Id", "x"));
    }

    @Test
    void givenComponentText_whenWritten_thenStyledSegmentsAndLongTextShortened() {
        Component c = Component.text("Rot ", NamedTextColor.RED).append(Component.text("fett").decorate(TextDecoration.BOLD));
        PanelDocument d = check(PanelWriter.write("a:b", Panel.page("T").children(Ui.text(c).id("c"), Ui.text("z".repeat(5000)).id("long")), player, null, null));
        PanelNode.Text t = (PanelNode.Text) d.find("c");
        assertEquals(0xFFFF5555, t.lines().getFirst().segments().getFirst().color());
        assertTrue(t.lines().getFirst().segments().get(1).bold());
        assertTrue(((PanelNode.Text) d.find("long")).lines().getFirst().plainText().length() <= PanelLimits.MAX_TEXT_CHARS);
    }

    @Test
    void givenSpecBuilder_whenBuilt_thenDefaultsAndValidation() {
        PanelSpec spec = PanelSpec.builder("Titel").render(ctx -> Panel.page("x")).build();
        assertTrue(spec.access().test(player));
        assertFalse(spec.dialogFallback());
        assertThrows(IllegalStateException.class, () -> PanelSpec.builder("t").build());   // render fehlt
    }
}
