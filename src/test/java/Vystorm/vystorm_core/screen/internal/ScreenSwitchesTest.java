package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenSwitchesTest {
    @Test void givenPluginName_whenListed_thenEveryScreenOfThatPluginIsOff() {
        assertTrue(ScreenRuntime.listed(List.of("vystormworlds"), "VystormWorlds", List.of("inventory:VystormWorlds", "inventory")));
        assertFalse(ScreenRuntime.listed(List.of("VystormWorlds"), "VystormChat", List.of("inventory:VystormChat", "inventory")));
    }

    @Test void givenGroupName_whenListed_thenAllKeysOfTheGroupAreOff() {
        assertTrue(ScreenRuntime.listed(List.of("panel"), "VystormMobs", List.of("panel:mobs-spawn", "panel")));
        assertTrue(ScreenRuntime.listed(List.of("panel"), "VystormMobs", List.of("panel:mobs-spawn")), "an entry matches keys that continue it after a colon");
        assertFalse(ScreenRuntime.listed(List.of("panel"), "VystormMobs", List.of("panels:x", "vystorm_core:menu")));
    }

    @Test void givenOneMenu_whenListed_thenOnlyThatMenuIsOff() {
        List<String> off = List.of(" Panel:Mobs-Spawn ", "", "dialog:XP banks");
        assertTrue(ScreenRuntime.listed(off, "VystormMobs", List.of("panel:mobs-spawn", "panel")));
        assertFalse(ScreenRuntime.listed(off, "VystormItems", List.of("panel:items-generate", "panel")));
        assertTrue(ScreenRuntime.listed(off, "vystorm_core", List.of("vystorm_core:dialog", "dialog:XP banks")));
        assertFalse(ScreenRuntime.listed(off, "vystorm_core", List.of("vystorm_core:dialog", "dialog:XP bank testbank")));
        assertFalse(ScreenRuntime.listed(List.of(), "vystorm_core", List.of("vystorm_core:menu")));
    }

    @Test void givenPanelOfAPlugin_whenNamingItsKeys_thenGroupFullAndBareNameAnswer() {
        ScreenSpec spec = ScreenSpec.builder("panel:vystormmobs:mobs-spawn", new ScreenLayout(1, 1, List.of())).build();
        List<String> keys = ScreenRuntime.keysOf(spec, new ScreenRuntime.Origin("panel:vystormmobs:mobs-spawn:", p -> { }, true));
        assertTrue(keys.containsAll(List.of("panel:vystormmobs:mobs-spawn", "panel", "vystormmobs:mobs-spawn", "mobs-spawn")), keys.toString());
        assertEquals(List.of("vystorm_core:menu"), ScreenRuntime.keysOf(ScreenSpec.builder("vystorm_core:menu", new ScreenLayout(1, 1, List.of())).build(), null));
    }
}
