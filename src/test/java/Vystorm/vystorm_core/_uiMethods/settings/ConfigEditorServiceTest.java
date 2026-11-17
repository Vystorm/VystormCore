package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class ConfigEditorServiceTest {
    static final String DEFAULTS = """
            # Example plugin
            enabled: true
            # Shown in chat
            prefix: "[Ex]"
            fields:
              # Largest field in blocks (1-64)
              max-size: 16
              # Seconds between two harvests
              cooldown: 30
              big-number: 9000000000
              speed: 1.5
              worlds:
                - world
                - world_nether
              rewards:
                - item: DIAMOND
                  amount: 1
              mode: normal
            database:
              host: localhost
              password: ""
              table: example
            web:
              api-key: ""
              secret-token: ""
            storage:
              mysql:
                port: 3306
            """;

    @TempDir Path folder;
    private MockedStatic<Bukkit> bukkit;
    private Plugin owner;
    private Player player;
    private SettingsRegistry registry;
    private ConfigEditorService editor;
    private final List<String[]> permissions = new ArrayList<>();
    private final Set<String> removed = new HashSet<>();
    private YamlConfiguration live;
    private final AtomicInteger saves = new AtomicInteger();

    @BeforeEach void setUp() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Example");
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getDataFolder()).thenReturn(folder.toFile());
        when(owner.getLogger()).thenReturn(Logger.getLogger("ConfigEditorServiceTest"));
        when(owner.getDescription()).thenReturn(new PluginDescriptionFile("Example", "1.0", "example.Main"));
        when(owner.getResource("config.yml")).thenAnswer(i -> new ByteArrayInputStream(DEFAULTS.getBytes(StandardCharsets.UTF_8)));
        when(owner.getConfig()).thenAnswer(i -> live());
        doAnswer(i -> { saves.incrementAndGet(); live().save(folder.resolve("config.yml").toFile()); return null; }).when(owner).saveConfig();
        player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Admin");
        when(player.hasPermission("vystorm_core.config.example")).thenReturn(true);
        when(player.hasPermission("vystorm_core.admin")).thenReturn(true);
        registry = new SettingsRegistry(mock(Plugin.class), () -> null, p -> {});
        editor = new ConfigEditorService(() -> mock(Plugin.class), () -> registry, () -> null, new ConfigEditorService.Permissions() {
            @Override public void add(String node, String description, String parent) { permissions.add(new String[] {node, description, parent}); }
            @Override public void remove(String node, String parent) { removed.add(node); }
        });
    }

    @AfterEach void tearDown() { bukkit.close(); }

    /** Plugin's getConfig(): loaded lazily from the data folder file with the jar defaults, like JavaPlugin. */
    private YamlConfiguration live() {
        if (live == null) {
            live = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
            live.setDefaults(ConfigEditorService.defaults(owner, "config.yml"));
        }
        return live;
    }

    private void writeConfig(String text) throws Exception { Files.writeString(folder.resolve("config.yml"), text); live = null; }

    private Map<String, Setting> settings() {
        Map<String, Setting> out = new LinkedHashMap<>();
        for (var view : registry.view(player)) for (var s : view.settings()) out.put(s.setting().id(), s.setting());
        return out;
    }

    @Test void givenDefaultFile_whenRegistering_thenValueTypesMapToMatchingInputs() throws Exception {
        writeConfig("enabled: false\nfields:\n  max-size: 32\n");
        assertTrue(editor.register(owner, "config.yml", null));
        Map<String, Setting> s = settings();
        assertEquals(Setting.Kind.TOGGLE, s.get("enabled").kind());
        assertEquals(false, s.get("enabled").getter().apply(player), "value comes from the data folder file");
        assertEquals(Setting.Kind.TEXT, s.get("prefix").kind());
        Setting maxSize = s.get("fields.max-size");
        assertEquals(Setting.Kind.NUMBER, maxSize.kind(), "comment range (1-64) gives a slider");
        assertEquals(1, maxSize.min()); assertEquals(64, maxSize.max());
        assertEquals(32d, maxSize.getter().apply(player));
        assertEquals("Max size", maxSize.label());
        assertEquals("Largest field in blocks (1-64)", maxSize.description());
        assertEquals(Setting.Kind.TEXT, s.get("fields.cooldown").kind(), "whole number without range = checked text");
        assertEquals("30", s.get("fields.cooldown").getter().apply(player), "missing key shows the jar default");
        assertEquals("9000000000", s.get("fields.big-number").getter().apply(player));
        assertEquals(Setting.Kind.TEXT, s.get("fields.speed").kind());
        assertEquals("world, world_nether", s.get("fields.worlds").getter().apply(player));
        assertNull(s.get("fields.rewards"), "list of maps is not editable");
        assertTrue(editor.fileOnly(owner).get("config.yml").contains("fields.rewards"));
        assertNotNull(s.get("database.table"));
        assertEquals("vystorm_core.config.example", permissions.getFirst()[0]);
        assertEquals(ConfigEditor.PERMISSION_ALL, permissions.getFirst()[2]);
        assertEquals(ConfigEditor.GROUP, registry.group("example:cfg-fields"));
        assertEquals(SettingPolicy.Activation.RELOAD, registry.policy("example:cfg-fields", "fields.cooldown").activation());
    }

    @Test void givenTextInputs_whenSaving_thenValuesAreValidatedAndTyped() throws Exception {
        writeConfig("fields:\n  cooldown: 30\n");
        editor.register(owner, "config.yml", null);
        var bad = registry.save(player, "example:cfg-fields", Map.of("fields.cooldown", "abc"), "test");
        assertEquals(0, bad.changed());
        assertEquals(SettingsRegistry.Problem.Kind.REJECTED, bad.problems().getFirst().kind());
        var ok = registry.save(player, "example:cfg-fields", Map.of("fields.cooldown", "45", "fields.speed", "2,25",
                "fields.worlds", "a, b ,c", "fields.max-size", 48d), "test");
        assertEquals(4, ok.changed(), ok.problems().toString());
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertEquals(45, disk.get("fields.cooldown"));
        assertEquals(2.25, disk.getDouble("fields.speed"));
        assertEquals(List.of("a", "b", "c"), disk.getStringList("fields.worlds"));
        assertEquals(48, disk.get("fields.max-size"));
        assertEquals(0, saves.get(), "the in-memory config is never dumped over the file");
        assertEquals(48, live().get("fields.max-size"), "the plugin's getConfig() has the new values");
    }

    @Test void givenSecretKeys_whenRegistering_thenTheyAreNeverShownOrExported() throws Exception {
        writeConfig("database:\n  host: db.example\n  password: hunter2\nweb:\n  api-key: k\n  secret-token: t\nstorage:\n  mysql:\n    port: 1\n");
        editor.register(owner, "config.yml", null);
        Set<String> ids = settings().keySet();
        for (String secret : List.of("database.host", "database.password", "web.api-key", "web.secret-token", "storage.mysql.port"))
            assertFalse(ids.contains(secret), secret);
        assertTrue(ConfigEditor.secret("database.shared.port"));
        assertTrue(ConfigEditor.secret("Storage.MySQL.database"));
        assertTrue(ConfigEditor.secret("web.gateway.url"));
        assertTrue(ConfigEditor.secret("x.credentials.name"));
        assertFalse(ConfigEditor.secret("web.public-url"));
        assertFalse(ConfigEditor.secret("fields.max-size"));

        assertTrue(ConfigEditor.secret("discord.webhook"));
        assertTrue(ConfigEditor.secret("alerts.discord-webhook-url"));
        assertTrue(ConfigEditor.secret("storage.jdbc-url"));
        assertTrue(ConfigEditor.secret("auth.dbPassword"));
        assertTrue(ConfigEditor.secret("bot.botToken"));
        assertTrue(ConfigEditor.secret("s3.secretKey"));
        assertTrue(ConfigEditor.secret("signing.private-key"));
        assertTrue(ConfigEditor.secret("redis.uri"));
        assertFalse(ConfigEditor.secret("database.allow-public-key-retrieval"));
        assertFalse(ConfigEditor.secret("web.client.hotkey-module"));

        // 0.27.1: whole configs are not profile-safe (thousands of values, profiles hold at most 96)
        int exported = SettingsProfiles.exportProfile(registry, player, folder.resolve("profiles"), "all");
        String profile = Files.readString(folder.resolve("profiles/all.yml"));
        assertEquals(0, exported);
        assertFalse(profile.contains("hunter2"));
        assertFalse(profile.contains("password"));
        assertFalse(profile.contains("db.example"));
        assertFalse(registry.policy("example:cfg-fields", "fields.cooldown").profileSafe());
        int selected = SettingsProfiles.exportProfile(registry, player, folder.resolve("profiles"), "selected",
                Set.copyOf(registry.keys(player)));
        assertTrue(selected > 0);
        String selectedProfile = Files.readString(folder.resolve("profiles/selected.yml"));
        assertFalse(selectedProfile.contains("hunter2")); assertFalse(selectedProfile.contains("password"));
        assertFalse(selectedProfile.contains("db.example"));
        live.set("fields.cooldown", 45);
        var draft = SettingsProfiles.importProfile(registry, player, folder.resolve("profiles"), "selected", Set.of("example"));
        assertTrue(draft.changes().stream().anyMatch(c -> c.id().equals("fields.cooldown")));
        assertEquals(45, live.getInt("fields.cooldown"), "selective preset stays a preview until confirmation");
    }

    @Test void givenManyConfigValues_whenExportingProfile_thenExportStillWorks() throws Exception {
        StringBuilder big = new StringBuilder("values:\n");
        for (int i = 0; i < 120; i++) big.append("  v").append(i).append(": ").append(i).append('\n');
        when(owner.getResource("big.yml")).thenAnswer(i -> new ByteArrayInputStream(big.toString().getBytes(StandardCharsets.UTF_8)));
        assertTrue(editor.register(owner, "big.yml", null));
        assertDoesNotThrow(() -> SettingsProfiles.exportProfile(registry, player, folder.resolve("profiles"), "many"));
    }

    @Test void givenOldConfig_whenMergingDefaults_thenMissingKeysAddedExistingKeptAndBackupWritten() throws Exception {
        writeConfig("# my own header\nenabled: false\nfields:\n  max-size: 40 # mine\n  extra: kept\nprefix: 5\n");
        int added = editor.mergeDefaults(owner, "config.yml");
        assertTrue(added >= 10, "added " + added);
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertFalse(disk.getBoolean("enabled"));
        assertEquals(40, disk.getInt("fields.max-size"));
        assertEquals("kept", disk.getString("fields.extra"));
        assertEquals(5, disk.getInt("prefix"), "existing values are never replaced, even with another type");
        assertEquals(30, disk.getInt("fields.cooldown"));
        assertEquals("example", disk.getString("database.table"));
        assertEquals(List.of("world", "world_nether"), disk.getStringList("fields.worlds"));
        String text = Files.readString(folder.resolve("config.yml"));
        assertTrue(text.contains("Seconds between two harvests"), "comments of added keys come from the default");
        assertTrue(text.contains("my own header"));
        try (var files = Files.list(folder)) {
            Path backup = files.filter(f -> f.getFileName().toString().startsWith("config.yml.bak-")).findFirst().orElseThrow();
            assertTrue(Files.readString(backup).contains("max-size: 40"));
        }
        assertEquals(30, live().getInt("fields.cooldown"), "the plugin's in-memory config gets the keys too");
        assertEquals(0, editor.mergeDefaults(owner, "config.yml"), "nothing left to add: no second backup");
        try (var files = Files.list(folder)) { assertEquals(1, files.filter(f -> f.getFileName().toString().contains(".bak-")).count()); }
    }

    @Test void givenRegister_whenFileMissing_thenTheDefaultIsWrittenOnce() throws Exception {
        assertTrue(editor.register(owner, "config.yml", null));
        assertTrue(Files.readString(folder.resolve("config.yml")).contains("# Example plugin"));
    }

    @Test void givenReloadHook_whenSaving_thenFileWrittenAndHookRunsOnceAndFailuresStayIsolated() throws Exception {
        writeConfig("enabled: true\n");
        AtomicInteger reloads = new AtomicInteger();
        editor.register(owner, "config.yml", reloads::incrementAndGet);
        var result = registry.save(player, "example:cfg-general", Map.of("enabled", false, "prefix", "[New]"), "test");
        assertEquals(2, result.changed());
        assertFalse(result.applyFailed());
        assertEquals(1, reloads.get());
        assertEquals(0, saves.get());
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertFalse(disk.getBoolean("enabled"));
        assertEquals("[New]", disk.getString("prefix"));
        assertEquals(SettingPolicy.Activation.IMMEDIATE, registry.policy("example:cfg-general", "enabled").activation());

        editor.register(owner, "config.yml", () -> { throw new IllegalStateException("boom"); });
        var failed = registry.save(player, "example:cfg-general", Map.of("enabled", true), "test");
        assertEquals(1, failed.changed(), "the value is saved even if the reload hook fails");
        assertTrue(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getBoolean("enabled"));
        verify(player, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test void givenNoPermission_whenViewing_thenConfigSectionsAreHidden() throws Exception {
        writeConfig("enabled: true\n");
        editor.register(owner, "config.yml", null);
        Player other = mock(Player.class);
        when(other.hasPermission("vystorm_core.admin")).thenReturn(true);
        assertTrue(registry.view(other).isEmpty(), "vystorm_core.admin alone is not the config node");
        assertTrue(editor.plugins(other).isEmpty());
        assertEquals(List.of("Example"), editor.plugins(player));
    }

    @Test void givenAnnotationsAfterRegister_whenLabelRangeChoicesHidden_thenSectionsAreRebuilt() throws Exception {
        writeConfig("enabled: true\n");
        editor.register(owner, "config.yml", null);
        editor.label(owner, "fields.cooldown", "Abklingzeit", "Sekunden");
        editor.range(owner, "fields.cooldown", 0, 600, 5);
        LinkedHashMap<String, String> modes = new LinkedHashMap<>(); modes.put("normal", "Normal"); modes.put("hard", "Schwer");
        editor.choices(owner, "fields.mode", modes);
        editor.hidden(owner, "fields.worlds");
        editor.activation(owner, "fields", SettingPolicy.Activation.RESTART);
        Map<String, Setting> s = settings();
        assertEquals("Abklingzeit", s.get("fields.cooldown").label());
        assertEquals(Setting.Kind.NUMBER, s.get("fields.cooldown").kind());
        assertEquals(600, s.get("fields.cooldown").max());
        assertEquals(Setting.Kind.CHOICE, s.get("fields.mode").kind());
        assertNull(s.get("fields.worlds"));
        assertEquals(SettingPolicy.Activation.RESTART, registry.policy("example:cfg-fields", "fields.mode").activation());
        assertEquals(List.of("cfg-general", "cfg-fields", "cfg-database"), editor.built(owner).stream().map(ConfigEditorService.Built::id).toList(),
                "no duplicates after rebuilds");
    }

    @Test void givenDisabledPlugin_whenReleased_thenSectionsAndPermissionAreRemoved() throws Exception {
        writeConfig("enabled: true\n");
        ConfigEditor.install(editor);
        try {
            editor.register(owner, "config.yml", null);
            assertFalse(registry.view(player).isEmpty());
            Vystorm.vystorm_core.lifecycle.CoreLifecycle.releaseAll(owner);
            assertTrue(registry.view(player).isEmpty());
            assertTrue(removed.contains("vystorm_core.config.example"));
            assertTrue(editor.plugins(player).isEmpty());
            assertTrue(editor.built(owner).isEmpty());
        } finally { ConfigEditor.install(null); }
    }

    // ------------------------------------------------------------------ 0.27.1: merge only config.yml unless opted in

    static final String SPAWNS = """
            # Spawn table
            spawns:
              zombie:
                # Weight of this entry
                weight: 10
                enabled: true
              skeleton:
                weight: 5
                enabled: true
            """;

    private Path spawnsFile() { return folder.resolve("spawns.yml"); }

    private long backups(String prefix) throws Exception {
        try (var files = Files.list(folder)) { return files.filter(f -> f.getFileName().toString().startsWith(prefix + ".bak-")).count(); }
    }

    @Test void givenTableFileWithDeletedEntry_whenRegisteredWithoutOptIn_thenTheEntryStaysDeleted() throws Exception {
        when(owner.getResource("spawns.yml")).thenAnswer(i -> new ByteArrayInputStream(SPAWNS.getBytes(StandardCharsets.UTF_8)));
        String admin = "spawns:\n  zombie:\n    weight: 3\n    enabled: true\n";
        Files.writeString(spawnsFile(), admin);
        assertTrue(editor.register(owner, "spawns.yml", null));
        editor.release(owner);
        assertTrue(editor.register(owner, "spawns.yml", null), "every enable registers again");
        assertEquals(admin, Files.readString(spawnsFile()), "nothing merged or rewritten on register");
        assertEquals(0, backups("spawns.yml"));

        Map<String, Setting> s = settings();
        assertEquals("5", s.get("spawns.skeleton.weight").getter().apply(player), "missing keys still show the jar default");
        assertEquals("3", s.get("spawns.zombie.weight").getter().apply(player));
        var result = registry.save(player, "example:cfg-spawns-spawns", Map.of("spawns.zombie.weight", "7"), "test");
        assertEquals(1, result.changed(), result.problems().toString());
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(spawnsFile().toFile());
        assertEquals(7, disk.getInt("spawns.zombie.weight"));
        assertFalse(disk.contains("spawns.skeleton"), "an untouched default is not written back");
        assertEquals(1, backups("spawns.yml"), "backup before the first editor save");
    }

    @Test void givenTableFile_whenRegisteredWithOptIn_thenMissingDefaultsAreMerged() throws Exception {
        when(owner.getResource("spawns.yml")).thenAnswer(i -> new ByteArrayInputStream(SPAWNS.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(spawnsFile(), "spawns:\n  zombie:\n    weight: 3\n    enabled: true\n");
        assertTrue(editor.register(owner, "spawns.yml", null, true));
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(spawnsFile().toFile());
        assertEquals(3, disk.getInt("spawns.zombie.weight"));
        assertEquals(5, disk.getInt("spawns.skeleton.weight"));
        assertEquals(1, backups("spawns.yml"));
    }

    @Test void givenConfigYml_whenRegisteredWithThreeArguments_thenMissingDefaultsAreMerged() throws Exception {
        writeConfig("enabled: false\n");
        assertTrue(editor.register(owner, "config.yml", null));
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertFalse(disk.getBoolean("enabled"));
        assertEquals(30, disk.getInt("fields.cooldown"));
        writeConfig("enabled: false\n");
        editor.release(owner);
        assertTrue(editor.register(owner, "config.yml", null, false), "explicit opt-out also for config.yml");
        assertEquals("enabled: false\n", Files.readString(folder.resolve("config.yml")));
    }

    @Test void givenMissingTableFile_whenSaving_thenTheCompleteDefaultIsWrittenWithTheChange() throws Exception {
        when(owner.getResource("spawns.yml")).thenAnswer(i -> new ByteArrayInputStream(SPAWNS.getBytes(StandardCharsets.UTF_8)));
        assertTrue(editor.register(owner, "spawns.yml", null));
        assertFalse(Files.exists(spawnsFile()), "register does not create other files");
        registry.save(player, "example:cfg-spawns-spawns", Map.of("spawns.skeleton.weight", "8"), "test");
        String text = Files.readString(spawnsFile());
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(spawnsFile().toFile());
        assertEquals(8, disk.getInt("spawns.skeleton.weight"));
        assertEquals(10, disk.getInt("spawns.zombie.weight"), "not only the changed key");
        assertTrue(text.contains("Weight of this entry"), "comments of the default kept");
    }

    // ------------------------------------------------------------------ 0.27.1 review: data safety of saves

    @Test void givenHandEditOnDisk_whenSavingThroughTheEditor_thenOnlyTheChangedKeyIsWritten() throws Exception {
        writeConfig("enabled: true\nprefix: \"[Ex]\"\n");
        editor.register(owner, "config.yml", null);
        live();
        // the admin edits the file by hand; the plugin has not reloaded yet
        String hand = Files.readString(folder.resolve("config.yml")).replace("[Ex]", "[Hand]") + "own-key: 1 # mine\n";
        Files.writeString(folder.resolve("config.yml"), hand);
        var result = registry.save(player, "example:cfg-general", Map.of("enabled", false), "test");
        assertEquals(1, result.changed());
        String text = Files.readString(folder.resolve("config.yml"));
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertFalse(disk.getBoolean("enabled"));
        assertEquals("[Hand]", disk.getString("prefix"), "hand edit is not overwritten by the stale in-memory config");
        assertEquals(1, disk.getInt("own-key"));
        assertTrue(text.contains("# mine"));
    }

    @Test void givenInvalidYamlOnDisk_whenSaving_thenNothingIsWrittenAndTheValueIsPutBack() throws Exception {
        writeConfig("enabled: true\n");
        editor.register(owner, "config.yml", null);
        live();
        String broken = "enabled: true\nfields: [unclosed\n  max-size: 40\n";
        Files.writeString(folder.resolve("config.yml"), broken);
        var result = registry.save(player, "example:cfg-general", Map.of("enabled", false), "test");
        assertTrue(result.applyFailed());
        assertEquals(broken, Files.readString(folder.resolve("config.yml")), "the admin's file is left alone");
        assertTrue(live().getBoolean("enabled"), "in-memory value put back");
        assertEquals(0, saves.get());
    }

    @Test void givenDottedKeys_whenSavingOrMerging_thenTheFileIsNotRewritten() throws Exception {
        String dotted = "enabled: true\nprices:\n  \"1.5\": cheap\n";
        writeConfig(dotted);
        assertEquals(0, editor.mergeDefaults(owner, "config.yml"));
        assertEquals(dotted, Files.readString(folder.resolve("config.yml")));
        assertEquals(0, backups("config.yml"));
        editor.register(owner, "config.yml", null);
        var result = registry.save(player, "example:cfg-general", Map.of("enabled", false), "test");
        assertTrue(result.applyFailed());
        assertEquals(dotted, Files.readString(folder.resolve("config.yml")));
        assertTrue(ConfigEditorService.hasDottedKeys(dotted));
        assertFalse(ConfigEditorService.hasDottedKeys("a:\n  b: 1.5\n"));
    }

    @Test void givenUntouchedLongText_whenSaving_thenNothingIsWrittenAndNoReload() throws Exception {
        String longText = "x".repeat(300);
        writeConfig("prefix: \"" + longText + "\"\n");
        AtomicInteger reloads = new AtomicInteger();
        editor.register(owner, "config.yml", reloads::incrementAndGet);
        long before = Files.getLastModifiedTime(folder.resolve("config.yml")).toMillis();
        String text = Files.readString(folder.resolve("config.yml"));
        long backupsBefore = backups("config.yml");
        registry.save(player, "example:cfg-general", Map.of("prefix", longText.substring(0, 256)), "test");
        assertEquals(text, Files.readString(folder.resolve("config.yml")));
        assertEquals(before, Files.getLastModifiedTime(folder.resolve("config.yml")).toMillis());
        assertEquals(0, reloads.get());
        assertEquals(backupsBefore, backups("config.yml"));
    }

    @Test void givenRestrictedFile_whenSaving_thenItsPermissionsAreKept() throws Exception {
        writeConfig("enabled: true\n");
        Path file = folder.resolve("config.yml");
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms;
        try { perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"); Files.setPosixFilePermissions(file, perms); }
        catch (UnsupportedOperationException notPosix) { return; }
        editor.register(owner, "config.yml", null);
        registry.save(player, "example:cfg-general", Map.of("enabled", false), "test");
        assertFalse(YamlConfiguration.loadConfiguration(file.toFile()).getBoolean("enabled"));
        assertEquals(perms, Files.getPosixFilePermissions(file));
    }

    @Test void givenMoreThan24Values_whenGrouping_thenPartsKeepSubgroupsAndStayWithinLimit() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 20; i++) paths.add("big.a.k" + i);
        for (int i = 0; i < 10; i++) paths.add("big.b.k" + i);
        for (int i = 0; i < 30; i++) paths.add("big.c.k" + i);
        var parts = ConfigEditorService.split("big", paths);
        assertTrue(parts.stream().allMatch(p -> p.size() <= ConfigEditor.MAX_PER_SECTION));
        assertEquals(60, parts.stream().mapToInt(List::size).sum());
        assertEquals(20, parts.getFirst().size(), "group a is not mixed with b when both do not fit");
    }

    @Test void givenPathsAndComments_whenNaming_thenReadableLabelsUniqueIdsAndRanges() {
        assertEquals("Max size", ConfigEditorService.label("fields.max-size"));
        assertEquals("Limits › Max players", ConfigEditorService.label("arena.limits.maxPlayers"));
        assertEquals("Enabled", ConfigEditorService.label("enabled"));
        Set<String> used = new HashSet<>();
        String a = ConfigEditorService.settingId("A.Key", used), b = ConfigEditorService.settingId("a.key", used);
        assertNotEquals(a, b);
        String longId = ConfigEditorService.settingId("x".repeat(100), used);
        assertTrue(longId.length() <= 64 && longId.matches("[a-z0-9_.-]+"));
        assertTrue(ConfigEditorService.sectionId("config.yml", "talent-world", 0, new HashSet<>()).matches("[a-z0-9_-]{1,40}"));
        assertEquals("cfg-spawns-mobs-2", ConfigEditorService.sectionId("spawns.yml", "mobs", 2, new HashSet<>()));
        assertArrayEquals(new double[] {1, 64}, ConfigEditorService.commentRange("Radius (1-64)", 16, true));
        assertArrayEquals(new double[] {0, 100}, ConfigEditorService.commentRange("Chance 0..100 percent", 5, true));
        assertArrayEquals(new double[] {2, 10}, ConfigEditorService.commentRange("Range 2 to 10", 4, true));
        assertNull(ConfigEditorService.commentRange("Radius (1-64)", 100, true), "default outside the range");
        assertNull(ConfigEditorService.commentRange("Wait 2-3 seconds after joining", 2, true), "loose numbers are no range");
        assertThrows(IllegalArgumentException.class, () -> ConfigEditorService.file("../evil.yml"));
    }

    // ------------------------------------------------------------------ 0.44.0: change history and undo

    @Test void givenHistory_whenSaving_thenEachChangedValueIsRecordedWithActorBeforeAndAfter() throws Exception {
        writeConfig("enabled: true\nfields:\n  cooldown: 30\n");
        ConfigHistory history = new ConfigHistory(folder.resolve("config-history.yml"), 50);
        editor.history(history);
        editor.register(owner, "config.yml", null);
        registry.save(player, "example:cfg-fields", Map.of("fields.cooldown", "45", "fields.speed", "2"), "test");

        List<ConfigHistory.Entry> entries = history.newestFirst(e -> true);
        assertEquals(1, entries.size());
        ConfigHistory.Entry entry = entries.getFirst();
        assertEquals("Admin", entry.actor());
        assertEquals("Example", entry.plugin());
        assertEquals("config.yml", entry.file());
        Map<String, ConfigHistory.Change> byPath = new LinkedHashMap<>();
        for (ConfigHistory.Change c : entry.changes()) byPath.put(c.path(), c);
        assertEquals(30, ((Number) byPath.get("fields.cooldown").before()).intValue());
        assertEquals(45, ((Number) byPath.get("fields.cooldown").after()).intValue());
        assertEquals(2.0, ((Number) byPath.get("fields.speed").after()).doubleValue());
        assertEquals(1, new ConfigHistory(folder.resolve("config-history.yml"), 50).size(), "the history survives a restart");
    }

    @Test void givenRecordedChange_whenUndoing_thenFileGetsOldValuesAndTheUndoIsRecorded() throws Exception {
        writeConfig("enabled: true\nprefix: \"[Ex]\"\n");
        ConfigHistory history = new ConfigHistory(folder.resolve("config-history.yml"), 50);
        editor.history(history);
        AtomicInteger reloads = new AtomicInteger();
        editor.register(owner, "config.yml", reloads::incrementAndGet);
        registry.save(player, "example:cfg-general", Map.of("enabled", false, "prefix", "[New]"), "test");
        int id = history.newestFirst(e -> true).getFirst().id();
        assertEquals(1, reloads.get());

        ConfigHistory.Entry undo = editor.undo(player, id, false);
        assertNotNull(undo);
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        assertTrue(disk.getBoolean("enabled"));
        assertEquals("[Ex]", disk.getString("prefix"));
        assertEquals(2, reloads.get(), "the plugin reloads after the undo");
        assertEquals(id, undo.undoOf());
        assertEquals(undo.id(), history.get(id).orElseThrow().undoneBy());
        assertNull(editor.undo(player, id, false), "a change is undone only once");
        assertNotNull(editor.undo(player, undo.id(), false), "the undo itself can be undone (redo)");
        assertFalse(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getBoolean("enabled"));
    }

    @Test void givenValueChangedAgain_whenUndoing_thenItNeedsForce() throws Exception {
        writeConfig("enabled: true\nfields:\n  cooldown: 30\n");
        ConfigHistory history = new ConfigHistory(null, 50);
        editor.history(history);
        editor.register(owner, "config.yml", null);
        registry.save(player, "example:cfg-fields", Map.of("fields.cooldown", "45"), "test");
        int first = history.newestFirst(e -> true).getFirst().id();
        registry.save(player, "example:cfg-fields", Map.of("fields.cooldown", "60"), "test");

        assertNull(editor.undo(player, first, false), "60 would be overwritten");
        assertEquals(60, YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getInt("fields.cooldown"));
        assertNotNull(editor.undo(player, first, true));
        assertEquals(30, YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getInt("fields.cooldown"));
    }

    @Test void givenOtherAdminWithoutPluginNode_whenUndoingOrListing_thenNothingHappens() throws Exception {
        writeConfig("enabled: true\n");
        ConfigHistory history = new ConfigHistory(null, 50);
        editor.history(history);
        editor.register(owner, "config.yml", null);
        registry.save(player, "example:cfg-general", Map.of("enabled", false), "test");
        Player other = mock(Player.class);
        when(other.getName()).thenReturn("Other");
        when(other.hasPermission("vystorm_core.admin")).thenReturn(true);
        int id = history.newestFirst(e -> true).getFirst().id();
        assertNull(editor.undo(other, id, false));
        assertFalse(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getBoolean("enabled"));
        clearInvocations(other);
        editor.sendHistory(other, null, 1);
        verify(other, never()).sendMessage(argThat((net.kyori.adventure.text.Component c) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains("#" + id)));
        editor.sendHistory(player, "example", 1);
        verify(player, atLeastOnce()).sendMessage(argThat((net.kyori.adventure.text.Component c) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).startsWith("#" + id)));
    }

    @Test void givenNoHistory_whenSaving_thenSavesWorkAsBefore() throws Exception {
        writeConfig("enabled: true\n");
        editor.register(owner, "config.yml", null);
        assertEquals(1, registry.save(player, "example:cfg-general", Map.of("enabled", false), "test").changed());
        assertNull(editor.undo(player, 1, false));
    }
}
