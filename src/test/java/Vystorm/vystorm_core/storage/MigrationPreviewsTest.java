package Vystorm.vystorm_core.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationPreviewsTest {
    @TempDir Path temp;
    private Plugin owner;

    @AfterEach
    void unregisterOwner() {
        if (owner == null) return;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            MigrationPreviews.unregister(owner);
        }
    }

    @Test
    void givenSourceOutsidePluginFolder_whenRegistering_thenRejectBeforeAdapterIsStored() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            owner = plugin("Example", temp.resolve("plugin-data"));
            assertThrows(IllegalArgumentException.class, () -> MigrationPreviews.register(
                    owner, "outside", temp.resolve("another-plugin/data.yml"), bytes -> report()));
            assertFalse(MigrationPreviews.keys().contains("example:outside"));
        }
    }

    @Test
    void givenSymlinkSource_whenRunning_thenRejectWithoutCallingConverterOrWritingFiles() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Path root = Files.createDirectories(temp.resolve("plugin-data"));
            owner = plugin("Example", root);
            Path outside = Files.writeString(temp.resolve("outside.yml"), "private");
            Path link = root.resolve("linked.yml");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException unsupported) {
                org.junit.jupiter.api.Assumptions.abort("Symlinks unsupported by provider");
            } catch (java.nio.file.FileSystemException unsupported) {
                if (!"\\".equals(link.getFileSystem().getSeparator())) throw unsupported;
                org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable: " + unsupported.getMessage());
            }
            AtomicBoolean called = new AtomicBoolean();
            MigrationPreviews.register(owner, "linked", link, bytes -> { called.set(true); return report(); });
            MigrationPreviews.Report symlinkReport = MigrationPreviews.run("example:linked");
            assertFalse(symlinkReport.problems().isEmpty());
            assertFalse(called.get(), "outside-target symlinks must not reach the converter");
        }
    }

    @Test
    void givenOversizedSource_whenRunning_thenRejectWithoutCallingConverterOrWritingFiles() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Path root = Files.createDirectories(temp.resolve("plugin-data"));
            owner = plugin("Example", root);
            AtomicBoolean called = new AtomicBoolean();
            Path oversized = root.resolve("large.bin");
            Files.write(oversized, new byte[8_388_609]);
            MigrationPreviews.register(owner, "large", oversized, bytes -> { called.set(true); return report(); });
            MigrationPreviews.Report largeReport = MigrationPreviews.run("example:large");
            assertFalse(largeReport.problems().isEmpty());
            assertFalse(called.get(), "oversized input must not reach the converter");
            assertTrue(Files.exists(oversized));
            assertEquals(8_388_609, Files.size(oversized));
        }
    }

    @Test
    void givenConverterMutatesDetachedSnapshot_whenPreviewRuns_thenSourceRemainsUnchangedAndNoFilesAreWritten() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Path root = Files.createDirectories(temp.resolve("plugin-data"));
            owner = plugin("Example", root);
            Path source = Files.write(root.resolve("source.bin"), new byte[] {1, 2, 3, 4});
            byte[] before = Files.readAllBytes(source);
            MigrationPreviews.register(owner, "copy", source, bytes -> {
                assertArrayEquals(before, bytes);
                bytes[0] = 99;
                return report();
            });

            MigrationPreviews.Report result = MigrationPreviews.run("example:copy");

            assertTrue(result.problems().isEmpty());
            assertArrayEquals(before, Files.readAllBytes(source), "converter receives detached bytes, never a writable store");
            try (var files = Files.list(root)) {
                assertEquals(List.of(source), files.toList(), "preview must create no output files");
            }
        }
    }

    private static MigrationPreviews.Report report() {
        return new MigrationPreviews.Report(1, 1, Map.of("xp", java.math.BigDecimal.TEN),
                Map.of("xp", java.math.BigDecimal.TEN), List.of());
    }

    private static Plugin plugin(String name, Path dataFolder) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }
}
