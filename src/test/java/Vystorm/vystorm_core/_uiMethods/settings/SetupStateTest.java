package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupStateTest {
    @TempDir Path dir;
    private SetupState state() { return new SetupState(dir.resolve("setup-state.yml")); }

    @Test void newInstallStaysPendingOnLaterBoots() throws Exception {
        var a = state(); a.observe("Winter", false);
        assertTrue(a.pending("winter"));
        var b = state(); b.observe("Winter", true);   // the plugin wrote its config after the first boot
        assertTrue(b.pending("Winter"));
    }
    @Test void existingConfigIsNeverPending() throws Exception {
        var a = state(); a.observe("Rails", true);
        assertFalse(a.pending("Rails"));
        assertEquals(SetupState.Status.EXISTING, a.status("Rails"));
    }
    @Test void finishedSetupsSurviveRestarts() throws Exception {
        var a = state(); a.observe("Rails", false); a.mark("Rails", SetupState.Status.DONE);
        var b = state(); b.observe("Rails", false);
        assertFalse(b.pending("Rails"));
        assertEquals(SetupState.Status.DONE, b.status("Rails"));
    }
    @Test void damagedFileIsKeptAndTreatedAsNoNagging() throws Exception {
        Files.writeString(dir.resolve("setup-state.yml"), "plugins: [");
        var a = state(); a.observe("Rails", false);
        assertFalse(a.pending("Rails"));
        assertTrue(Files.readString(dir.resolve("setup-state.yml")).contains("plugins: ["));
    }
}
