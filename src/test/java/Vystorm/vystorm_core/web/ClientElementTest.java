package Vystorm.vystorm_core.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiElement;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonObject;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ClientElementTest {

    static UiElement parse(ClientElement.Element<?> e) {
        return UiSchema.element(UiSchema.parseObject(e.toJson().toString(), Protocol.MAX_ELEMENT_JSON_BYTES));
    }

    @Test
    void givenBuilders_whenSerialized_thenAcceptedByClientSchema() {
        UiElement.Bar bar = (UiElement.Bar) parse(ClientElement.bar("xp").anchor(ClientElement.Anchor.BOTTOM).offset(0, 52)
                .value(340).max(1000).color("#FF7CFC00").label(ClientElement.Text.of("Lv 12", "light_purple").bold().then(" · 340/1000"))
                .segments(10).width(182).height(5));
        assertEquals(340, bar.value());
        assertEquals(10, bar.segments());
        UiElement.Slots slots = (UiElement.Slots) parse(ClientElement.slots("bar").anchor(ClientElement.Anchor.BOTTOM_RIGHT)
                .slot(ClientElement.slot().icon(ClientElement.Icon.item("minecraft:blaze_powder")).key("R").cooldown(8000, 10000))
                .slot(ClientElement.slot().icon(ClientElement.Icon.image("/m/items/icons/flame.png")).key("G").active(true)));
        assertEquals(2, slots.slots().size());
        assertEquals(8000, slots.slots().getFirst().cooldownMs());
        UiElement.Panel panel = (UiElement.Panel) parse(ClientElement.panel("info").title("Charakter").line("Level 3")
                .line(ClientElement.Text.of("2 Punkte frei", "gold")).icon(ClientElement.Icon.sprite("minecraft:hud/heart/full")).width(120));
        assertEquals(2, panel.lines().size());
        UiElement.Marker marker = (UiElement.Marker) parse(ClientElement.marker("m").world("minecraft:overworld").at(440, 70, -184).label("Dorf"));
        assertEquals(-184, marker.z());
        UiElement.Tooltip tooltip = (UiElement.Tooltip) parse(ClientElement.tooltip("t").matchItem("minecraft:diamond_sword")
                .matchCustomData("PublicBukkitValues.vystorm_items:id", "flame_blade").line("Feuerschaden +5"));
        assertEquals(1, tooltip.lines().size());
        UiSchema.toast(UiSchema.parseObject(ClientElement.toast("Level 13").text("+1 Talentpunkt").duration(4000)
                .style(ClientElement.Toast.Style.SUCCESS).toJson().toString(), Protocol.MAX_TOAST_JSON_BYTES));
    }

    @Test
    void givenPatchBuilder_whenSerialized_thenOnlySetFields() {
        JsonObject patch = ClientElement.bar("xp").value(347).label("Lv 12").toJson();
        assertEquals(List.of("id", "type", "value", "label"), List.copyOf(patch.keySet()));
        assertEquals("Lv 12", patch.get("label").getAsString(), "plain text collapses to a string");
    }

    @Test
    void givenHiddenCharacters_whenBuilt_thenStrippedLikeTheClient() {
        JsonObject bar = ClientElement.bar("xp").label("Lv\u202E 12\u200B§c\u2028!").toJson();
        assertEquals("Lv 12c!", bar.get("label").getAsString());
        assertEquals("R", ClientElement.slot().key("\u2066R").json.get("key").getAsString());
    }

    @Test
    void givenBukkitWorldName_whenMarkerWorldSet_thenDimensionKeySent() {
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft("overworld"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(org.bukkit.Server.class));
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            assertEquals("minecraft:overworld", ClientElement.marker("m").world("world").json.get("world").getAsString());
            assertEquals("minecraft:the_nether", ClientElement.marker("m").world("minecraft:the_nether").json.get("world").getAsString());
            assertEquals("unknown", ClientElement.marker("m").world("unknown").json.get("world").getAsString());
        }
    }

    @Test
    void givenCooldownBelowZero_whenBuilt_thenClamped() {
        JsonObject slot = ClientElement.slot().cooldown(-5, 0).json;
        assertEquals(0, slot.get("cooldownMs").getAsLong());
        assertEquals(0, slot.get("cooldownTotalMs").getAsLong());
    }

    @Test
    void givenPluginNames_whenNamespaced_thenSafeIds() {
        assertEquals("vystormskills", ClientUi.namespace("VystormSkills"));
        assertEquals("my_plugin", ClientUi.namespace("My Plugin"));
        assertEquals("p_x", ClientUi.namespace("_x"));
        assertEquals("xp", ClientUi.localId("vystormskills:xp"));
    }

    @Test
    void givenOwner_whenIds_thenPrefixedOnceAndOwnerResolved() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("TestPluginUi");
        ClientUi ui = ClientUi.of(plugin);
        assertSame(ui, ClientUi.of(plugin));
        assertEquals("testpluginui:xp", ui.fullId("xp"));
        assertEquals("testpluginui:xp", ui.fullId("testpluginui:xp"));
        assertSame(plugin, ClientUi.ownerOf("testpluginui:xp").orElseThrow());
        assertTrue(ClientUi.ownerOf("unknown:xp").isEmpty());
        ClientUi.release(plugin);
        assertTrue(ClientUi.ownerOf("testpluginui:xp").isEmpty());
    }

    @Test
    void givenNoChannel_whenSending_thenSilentlyFalse() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("NoChannel");
        Player player = mock(Player.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ClientUi ui = ClientUi.of(plugin);
            assertFalse(ui.available(player, ClientServices.CAP_HUD));
            assertFalse(ui.put(player, ClientElement.bar("xp").value(1)));
            assertFalse(ui.patch(player, ClientElement.bar("xp").value(2)));
            assertFalse(ui.toast(player, ClientElement.toast("x")));
            assertFalse(ui.has(player, "xp"));
            assertFalse(ClientServices.uiAvailable(player, ClientServices.CAP_HUD));
        }
    }

    @Test
    void givenOffThread_whenSending_thenIllegalState() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("OffThread");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> ClientUi.of(plugin).put(mock(Player.class), ClientElement.bar("xp")));
        }
    }
}
