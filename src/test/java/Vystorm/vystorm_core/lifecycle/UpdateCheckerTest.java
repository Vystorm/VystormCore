package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
    @Test void comparesNumericVersionsAndIgnoresSuffixes() {
        assertTrue(UpdateChecker.newer("1.29.1-26.2", "1.30.0-26.2"));
        assertTrue(UpdateChecker.newer("0.39.1-Vystorm-Core-26.2", "0.40.0"));
        assertTrue(UpdateChecker.newer("1.2", "1.2.1"));
        assertFalse(UpdateChecker.newer("1.30.0-26.2", "1.30.0"));
        assertFalse(UpdateChecker.newer("1.30.0", "1.29.9"));
        assertFalse(UpdateChecker.newer("1.10.0", "1.9.9"));
        assertTrue(UpdateChecker.newer("v1.9.9", "1.10.0"));
        assertFalse(UpdateChecker.newer("unknown", "1.0"));
        assertFalse(UpdateChecker.newer("1.0", null));
    }

    @Test void givenIgnoreRulesWhenMatchingThenNameSilencesAllAndVersionOnlyThatOne() {
        var rules = java.util.List.of("customtrees", "VystormVeins 1.4.0", "  ", "Winter Is Coming");
        assertTrue(UpdateChecker.ignored(rules, "CustomTrees", "1.30.0-26.2"));
        assertTrue(UpdateChecker.ignored(rules, "VystormVeins", "1.4.0-26.2"));
        assertTrue(UpdateChecker.ignored(rules, "VystormVeins", "1.4"));
        assertFalse(UpdateChecker.ignored(rules, "VystormVeins", "1.4.1"));
        assertFalse(UpdateChecker.ignored(rules, "VystormVeins", null));
        assertTrue(UpdateChecker.ignored(rules, "Winter Is Coming", "2.0"));
        assertFalse(UpdateChecker.ignored(rules, "XPVaultEconomy", "2.0"));
        assertFalse(UpdateChecker.ignored(null, "CustomTrees", "1.0"));
        assertFalse(UpdateChecker.ignored(java.util.List.of(), "CustomTrees", "1.0"));
    }

    @Test void givenTimestampsWhenComputingMinutesAgoThenWholeMinutesAndNeverNegative() {
        assertEquals(0, UpdateChecker.minutesAgo(1_000_000, 1_000_000 + 59_999));
        assertEquals(12, UpdateChecker.minutesAgo(0, 12 * 60_000L + 5));
        assertEquals(0, UpdateChecker.minutesAgo(5_000, 1_000));
    }

    @Test void resultLinksToTheSpigotUpdates() {
        var result = new UpdateChecker.Result("CustomTrees", 139850, "1.29.1-26.2", "1.30.0-26.2");
        assertTrue(result.newer());
        assertEquals("https://www.spigotmc.org/resources/139850/updates", result.url());
        assertFalse(new UpdateChecker.Result("CustomTrees", 139850, "1.29.1", null).newer());
    }
}
