package Vystorm.vystorm_core._uiMethods.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core.lifecycle.CoreSwitches;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The admin subcommands that belong to a switch (0.65.0) answer the shared message while it is off. */
class CoreAdminSwitchesTest {
    @AfterEach void allOn() { CoreSwitches.configure(null); }

    @Test void givenTheAdminSubcommands_whenTheirSwitchIsAsked_thenOnlyDiagnosticsLootAndSetupHaveOne() {
        assertEquals(CoreSwitches.Switch.DIAGNOSTICS, CoreAdminDashboard.switchOf("diagnostics"));
        assertEquals(CoreSwitches.Switch.DIAGNOSTICS, CoreAdminDashboard.switchOf("trace"));
        assertEquals(CoreSwitches.Switch.DIAGNOSTICS, CoreAdminDashboard.switchOf("migration"));
        assertEquals(CoreSwitches.Switch.LOOT, CoreAdminDashboard.switchOf("loot"));
        assertEquals(CoreSwitches.Switch.SETUP, CoreAdminDashboard.switchOf("setup"));
        for (String base : new String[] {"config", "features", "language", "placeholders", "commands", "permissions"})
            assertNull(CoreAdminDashboard.switchOf(base), base + " is part of the base and stays on");
    }

    @Test void givenDiagnosticsOff_whenItsSubcommandRuns_thenItIsStoppedAndOnAsBefore() {
        Player admin = mock(Player.class);
        assertFalse(CoreAdminDashboard.switchedOff(admin, "diagnostics"), "on: runs as before");
        YamlConfiguration config = new YamlConfiguration();
        config.set(CoreSwitches.Switch.DIAGNOSTICS.path, false);
        CoreSwitches.configure(config);
        assertTrue(CoreAdminDashboard.switchedOff(admin, "trace"));
        assertTrue(CoreAdminDashboard.switchedOff(admin, "diagnostics"));
        assertFalse(CoreAdminDashboard.switchedOff(admin, "loot"), "other switches stay on");
        assertFalse(CoreAdminDashboard.switchedOff(admin, "features"));
    }
}
