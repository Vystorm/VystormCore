package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsProfilesTest {
    @TempDir Path folder;

    @Test
    void givenProfileSafeMetadata_whenExporting_thenOnlySafeServerValuesAreWritten() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Fixture f = new Fixture();
            AtomicInteger setterCalls = new AtomicInteger();
            AtomicBoolean safeValue = new AtomicBoolean(false);
            f.register(Setting.toggle("safe", "Safe", "", p -> safeValue.get(),
                    (p, value) -> { setterCalls.incrementAndGet(); return null; }),
                    Setting.text("private", "Private", "", 40, p -> "secret",
                            (p, value) -> { setterCalls.incrementAndGet(); return null; }));
            f.registry.policy(f.owner, "main", "safe", new SettingPolicy(SettingPolicy.Activation.RELOAD, true));
            assertEquals(1, SettingsProfiles.exportProfile(f.registry, f.player, folder, "portable"));
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folder.resolve("portable.yml").toFile());
            assertEquals(1, yaml.getMapList("values").size());
            assertEquals("safe", yaml.getMapList("values").getFirst().get("setting"));
            safeValue.set(true);
            SettingsDraft draft = SettingsProfiles.importProfile(f.registry, f.player, folder, "portable");
            assertEquals(1, draft.changes().size());
            assertEquals(false, draft.changes().getFirst().after());
            assertEquals(0, setterCalls.get(), "export must not apply or save settings");
        }
    }

    @Test
    void givenVersionMismatchRevokedPermissionOrNonMapRow_whenImporting_thenRejectBeforeWrites() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Fixture f = new Fixture();
            AtomicInteger setterCalls = new AtomicInteger(), saves = new AtomicInteger();
            f.register(Setting.number("limit", "Limit", "", 0, 20, 1, p -> 2d,
                    (p, value) -> { setterCalls.incrementAndGet(); return null; }), saves::incrementAndGet);
            f.registry.policy(f.owner, "main", "limit", new SettingPolicy(SettingPolicy.Activation.RELOAD, true));

            writeProfile("mismatch.yml", "values:\n  - section: example:main\n    setting: limit\n    version: 0.9\n    value: 5\n");
            assertThrows(java.io.IOException.class,
                    () -> SettingsProfiles.importProfile(f.registry, f.player, folder, "mismatch"));
            assertEquals(0, setterCalls.get());
            assertEquals(0, saves.get());

            writeProfile("malformed.yml", "values:\n  - invalid-row\n");
            assertThrows(java.io.IOException.class,
                    () -> SettingsProfiles.importProfile(f.registry, f.player, folder, "malformed"));

            when(f.player.hasPermission("vystorm_core.admin")).thenReturn(false);
            assertThrows(SecurityException.class,
                    () -> SettingsProfiles.importProfile(f.registry, f.player, folder, "mismatch"));
            assertEquals(0, setterCalls.get());
            assertEquals(0, saves.get());
        }
    }

    private void writeProfile(String filename, String rows) throws Exception {
        Files.writeString(folder.resolve(filename), "format: 1\n" + rows);
    }

    @Test
    void givenSharedPreset_whenSelectingInstalledPlugin_thenOtherPluginsAreUntouchedAndConfirmationIsRequired() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Fixture f = new Fixture();
            AtomicInteger current = new AtomicInteger(2), saves = new AtomicInteger();
            f.register(Setting.number("limit", "Limit", "", 0, 20, 1, p -> (double) current.get(),
                    (p, value) -> { current.set(value.intValue()); return null; }), saves::incrementAndGet);
            f.registry.policy(f.owner, "main", "limit", new SettingPolicy(SettingPolicy.Activation.RELOAD, true));
            writeProfile("shared.yml", "values:\n  - section: absent:main\n    setting: limit\n    version: '1.0'\n    value: 9\n  - section: example:main\n    setting: limit\n    version: '1.0'\n    value: 7\n");
            SettingsDraft draft = SettingsProfiles.importProfile(f.registry, f.player, folder, "shared", java.util.Set.of("Example"));
            assertEquals(2, current.get()); assertEquals(0, saves.get()); assertEquals(1, draft.changes().size());
            assertEquals(1, draft.apply(f.registry, f.player).getFirst().changed());
            assertEquals(7, current.get()); assertEquals(1, saves.get());
            assertTrue(draft.apply(f.registry, f.player).isEmpty(), "confirmation is single-use");
            assertTrue(SettingsProfiles.importProfile(f.registry, f.player, folder, "shared", java.util.Set.of("example")).changes().isEmpty());
        }
    }

    @Test
    void givenWizardSelection_whenUnknownPluginOrPathRequested_thenReject() throws Exception {
        assertEquals(java.util.Set.of("example"), SetupWizard.selection(" Example,example ", List.of("example")));
        assertThrows(IllegalArgumentException.class, () -> SetupWizard.selection("example,unknown", List.of("example")));
        assertThrows(IllegalArgumentException.class, () -> SetupWizard.selection("", List.of("example")));
        Files.writeString(folder.resolve("safe.yml"), "format: 1\nvalues: []\n");
        Files.writeString(folder.resolve("bad name.yml"), "");
        assertEquals(List.of("safe"), SetupWizard.profiles(folder));
    }

    private static final class Fixture {
        final Plugin owner = mock(Plugin.class);
        final Player player = mock(Player.class);
        final SettingsRegistry registry = new SettingsRegistry(owner, () -> null, p -> {});
        Fixture() {
            when(owner.getName()).thenReturn("Example");
            when(owner.isEnabled()).thenReturn(true);
            when(owner.getDescription()).thenReturn(new PluginDescriptionFile("Example", "1.0", "example.Main"));
            when(player.hasPermission("vystorm_core.admin")).thenReturn(true);
        }
        void register(Setting setting, Runnable afterSave) {
            registry.register(owner, SettingsSection.server("main", "Main", "", "vystorm_core.admin",
                    List.of(setting), p -> afterSave.run()));
        }
        void register(Setting... settings) {
            registry.register(owner, SettingsSection.server("main", "Main", "", "vystorm_core.admin",
                    List.of(settings), p -> {}));
        }
    }
}
