package Vystorm.vystorm_core.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

/** 0.26.4: Test-Hilfe für native Elemente und die neuen Builder. */
class ClientUiTestingTest {
    @Test
    void givenValidAndInvalidElements_whenValidated_thenErrorsNameTheField() {
        assertEquals(List.of(), ClientUiTesting.validate(ClientElement.bar("xp").value(3).max(10)));
        List<String> errors = ClientUiTesting.validate(ClientElement.bar("xp").width(5000));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("width"), errors.toString());
        assertFalse(ClientUiTesting.validate(ClientElement.tooltip("t").line("x")).isEmpty(), "match missing");
        assertFalse(ClientUiTesting.validate(ClientElement.list("l")).isEmpty(), "list needs title or rows");
    }

    @Test
    void givenNewBuilders_whenValidated_thenAcceptedAndExtensionNeedReported() {
        ClientElement.HudList list = ClientElement.list("quests").title("Quests").stack(true)
                .row(ClientElement.row("Holz").right("3/10").bar(3, 10, null));
        assertEquals(List.of(), ClientUiTesting.validate(list));
        assertTrue(ClientUiTesting.needsExtensions(list));

        ClientElement.Tooltip tip = ClientElement.tooltip("soul").matchCustomDataViewer("PublicBukkitValues.vystorm_items:soulbound")
                .matchCustomDataPresent("PublicBukkitValues.vystorm_items:id")
                .line("Qualität: " + ClientElement.data("PublicBukkitValues.vystorm_items:quality"));
        assertEquals(List.of(), ClientUiTesting.validate(tip));
        assertTrue(ClientUiTesting.needsExtensions(tip));
        assertEquals(2, tip.toJson().getAsJsonObject("match").getAsJsonArray("customData").size(), "conditions are AND-ed in a list");
        ClientElement.Tooltip legacy = ClientElement.tooltip("t").matchCustomData("a", "b").line("x");
        assertTrue(legacy.toJson().getAsJsonObject("match").get("customData").isJsonObject(), "single equals keeps the 0.8 form");
        assertFalse(ClientUiTesting.needsExtensions(legacy));

        ClientElement.Bar countdown = ClientElement.bar("wave").countdown(75_000, 90_000).label("Welle in " + ClientElement.Bar.TIME);
        assertEquals(List.of(), ClientUiTesting.validate(countdown));
        assertEquals(-1, countdown.toJson().get("rate").getAsDouble());
        assertEquals(75, countdown.toJson().get("value").getAsDouble());
        assertTrue(ClientUiTesting.needsExtensions(countdown));
        assertFalse(ClientUiTesting.needsExtensions(ClientElement.bar("b").rate(3).stack(true)), "optional fields only");

        ClientElement.Marker marker = ClientElement.marker("npc").at(1, 64, 2).entity(UUID.randomUUID()).yOffset(0.5).ground(true);
        assertEquals(List.of(), ClientUiTesting.validate(marker));
        assertFalse(ClientUiTesting.needsExtensions(marker));
        assertThrows(IllegalArgumentException.class, () -> ClientUiTesting.needsExtensions(ClientElement.bar("b").width(1)));
    }

    @Test
    void givenListRowCountdown_whenValidated_thenRunsOnTheClient() {
        ClientElement.HudList events = ClientElement.list("events").title("World events")
                .row(ClientElement.row("Meteor").countdown(90_000, 120_000, "red").right(ClientElement.Bar.TIME))
                .row(ClientElement.row("Market").bar(3, 10, null).rate(0.5).right(ClientElement.Bar.VALUE + "/" + ClientElement.Bar.MAX));
        assertEquals(List.of(), ClientUiTesting.validate(events));
        com.google.gson.JsonObject bar = events.toJson().getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("bar");
        assertEquals(90, bar.get("value").getAsDouble());
        assertEquals(120, bar.get("max").getAsDouble());
        assertEquals(-1, bar.get("rate").getAsDouble());
        assertThrows(IllegalStateException.class, () -> ClientElement.row("x").rate(1), "rate needs a bar");
    }

    @Test
    void givenToastWithAction_whenValidated_thenLikeClientUiSendsIt() {
        assertEquals(List.of(), ClientUiTesting.validate(ClientElement.toast("Einladung").action("Beitreten")));
        assertFalse(ClientUiTesting.validate(ClientElement.toast("x").action("y".repeat(40))).isEmpty(), "label ≤ 32 chars");
        assertFalse(ClientUiTesting.validate(ClientElement.toast("x").duration(60_000)).isEmpty());
    }

    @Test
    void givenItemStack_whenIcon_thenItemModelIncluded() {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getType()).thenReturn(Material.STICK);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasItemModel()).thenReturn(true);
        when(meta.getItemModel()).thenReturn(NamespacedKey.fromString("vystorm:items/flame_blade"));
        ClientElement.Panel panel = ClientElement.panel("p").title("t").icon(ClientElement.Icon.item(stack));
        assertEquals("vystorm:items/flame_blade", panel.toJson().getAsJsonObject("icon").get("model").getAsString());
        assertEquals(List.of(), ClientUiTesting.validate(panel));
    }
}
