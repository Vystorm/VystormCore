package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.VisitTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core.worlds.ExitReason;
import Vystorm.vystorm_core.worlds.ManagedWorldException;
import Vystorm.vystorm_core.worlds.WorldSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review I3: Neuaufbau nur mit Markierung, nie an geladenen oder fremden Welten. */
class ManagedWorldRegistryTest {
    @TempDir Path dir;
    Host host;
    ManagedWorldRegistry registry;
    final Plugin skills = plugin("VystormSkills");
    final List<String> ended = new ArrayList<>();

    /** Legt „Welten“ als Ordner an; {@code realFolder} simuliert einen anderen echten Pfad als geraten. */
    final class Host implements ManagedWorldRegistry.Host {
        final Map<String, World> loaded = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        Path realFolder;
        Path folderAfterCreate;
        boolean unloadSucceeds = true;
        boolean stopping;
        public boolean stopping() { return stopping; }
        public Path folderFor(WorldSpec spec) { return realFolder != null ? realFolder : defaultFolder(spec.worldName()); }

        @Override public boolean primaryThread() { return true; }
        @Override public World loaded(String name) { return loaded.get(name); }
        @Override public Path defaultFolder(String worldName) { return dir.resolve("level/dimensions/minecraft").resolve(worldName); }

        @Override public World create(WorldSpec spec) {
            calls.add("create");
            World world = world(spec.worldName());
            loaded.put(spec.worldName(), world);
            return world;
        }

        @Override public boolean unload(World world, boolean save) {
            calls.add("unload save=" + save);
            if (!unloadSucceeds) return false;
            loaded.values().remove(world);return true;
        }
        @Override public Path folderOf(World world) { return folderAfterCreate != null ? folderAfterCreate : realFolder != null ? realFolder : defaultFolder(world.getName()); }
        @Override public void configure(World world, WorldSpec spec) { calls.add("configure"); }
        @Override public void attach(Plugin owner, World world, WorldSpec spec) { calls.add("attach"); }
        @Override public void detach(World world, WorldSpec spec) { calls.add("detach"); }
        @Override public void evacuate(World world) { calls.add("evacuate"); }
        @Override public void log(String message) { calls.add("log " + message); }
    }

    @BeforeEach
    void setUp() {
        host = new Host();
        registry = new ManagedWorldRegistry(host, dir.resolve("core/worlds"));
        registry.endVisitsWith((id, reason) -> ended.add(id + " " + reason));
    }

    WorldSpec spec(String hash) {
        return WorldSpec.builder("vystormskills:talentwelt", "vystorm_talentwelt", mock(ChunkGenerator.class)).layoutHash(hash)
                .legacyHashFile("vystorm-layout.txt").build();
    }

    Path folder() { return host.defaultFolder("vystorm_talentwelt"); }

    void chunkFile(Path folder) throws Exception {
        Files.createDirectories(folder.resolve("region"));
        Files.writeString(folder.resolve("region/r.0.0.mca"), "chunks");
    }

    @Test
    void givenNewWorld_whenRegistered_thenMarkerWrittenAndRebuiltFlagSet() throws Exception {
        var world = registry.register(skills, spec("h1"));
        assertTrue(world.rebuilt());
        assertEquals("h1", WorldFolders.readMarker(folder(), "vystormskills:talentwelt", null).orElseThrow().hash());
        assertSame(world, registry.byWorld(world.world()));
        assertEquals(List.of("create", "configure", "attach"), host.calls.stream().filter(c -> !c.startsWith("log")).toList());
    }

    @Test
    void givenRegisteredWorld_whenMarkerIsWritten_thenNoObsoletePathTrackingFileOrDirectoryIsCreated() {
        registry.register(skills, spec("h1"));
        assertFalse(Files.exists(dir.resolve("core/worlds")));
    }

    @Test
    void givenSameHash_whenRegisteredAgain_thenChunksKept() throws Exception {
        registry.register(skills, spec("h1"));
        chunkFile(folder());
        registry.unregister(skills, "vystormskills:talentwelt");
        var again = registry.register(skills, spec("h1"));
        assertFalse(again.rebuilt());
        assertTrue(Files.exists(folder().resolve("region/r.0.0.mca")));
    }

    @Test
    void givenChangedHash_whenRegistered_thenChunkDataWipedBeforeLoad() throws Exception {
        registry.register(skills, spec("h1"));
        chunkFile(folder());
        registry.unregister(skills, "vystormskills:talentwelt");
        var again = registry.register(skills, spec("h2"));
        assertTrue(again.rebuilt());
        assertFalse(Files.exists(folder().resolve("region/r.0.0.mca")));
        assertEquals("h2", WorldFolders.readMarker(folder(), "vystormskills:talentwelt", null).orElseThrow().hash());
    }

    @Test
    void givenForeignFolderWithChunksButNoMarker_whenRegistered_thenRefusedAndNothingDeleted() throws Exception {
        chunkFile(folder());
        var failure = assertThrows(ManagedWorldException.class, () -> registry.register(skills, spec("h1")));
        assertEquals(ManagedWorldException.Reason.FOREIGN_FOLDER, failure.reason());
        assertTrue(Files.exists(folder().resolve("region/r.0.0.mca")));
        assertFalse(host.calls.contains("create"));
    }

    @Test
    void givenAlreadyLoadedWorld_whenRegistered_thenRefusedWithoutTouchingIt() {
        host.loaded.put("vystorm_talentwelt", world("vystorm_talentwelt"));
        var failure = assertThrows(ManagedWorldException.class, () -> registry.register(skills, spec("h1")));
        assertEquals(ManagedWorldException.Reason.ALREADY_LOADED, failure.reason());
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void givenLegacySkillsHashFile_whenRegistered_thenAdoptedAndRebuiltOnlyIfHashDiffers() throws Exception {
        chunkFile(folder());
        Files.writeString(folder().resolve("vystorm-layout.txt"), "h1");
        assertFalse(registry.register(skills, spec("h1")).rebuilt(), "legacy marker with same hash: keep chunks");
        assertTrue(Files.exists(folder().resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(folder().resolve(WorldFolders.MARKER)));
    }

    @Test
    void givenGuessedPathWrong_whenRealFolderHasStaleData_thenWipeBeforeFirstCreate() throws Exception {
        Path real = dir.resolve("elsewhere/vystorm_talentwelt");
        chunkFile(real);
        WorldFolders.writeMarker(real, new WorldFolders.Marker("vystormskills:talentwelt", "VystormSkills", "old", false));
        host.realFolder = real;
        var world = registry.register(skills, spec("new"));
        assertTrue(world.rebuilt());
        assertEquals(real, world.folder());
        assertFalse(Files.exists(real.resolve("region/r.0.0.mca")));
        assertEquals(List.of("create", "configure", "attach"), host.calls.stream().filter(c -> !c.startsWith("log")).toList());
    }

    @Test
    void givenHostReturnsUnexpectedFolderAfterCreate_whenRegistered_thenWorldIsUnloadedWithoutSavingBeforeFailure() {
        host.folderAfterCreate = dir.resolve("actual/world");
        assertEquals(ManagedWorldException.Reason.BUKKIT_FAILED,
                assertThrows(ManagedWorldException.class, () -> registry.register(skills, spec("h1"))).reason());
        assertEquals(List.of("create", "unload save=false"), host.calls);
        assertNull(host.loaded("vystorm_talentwelt"));
    }

    @Test
    void givenServerStopping_whenReleased_thenVisitsEndAndDetachWithoutBukkitUnload() {
        var managed = registry.register(skills, spec("h1"));
        host.calls.clear();host.stopping = true;
        registry.release(skills);
        assertEquals(List.of("vystormskills:talentwelt SHUTDOWN"), ended);
        assertEquals(List.of("detach"), host.calls);
        assertNull(managed.world());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void givenAutoSaveWorld_whenUnregistered_thenSavesAsDocumented() {
        WorldSpec auto = WorldSpec.builder("p:w", "autosaved", mock(ChunkGenerator.class)).autoSave(true).build();
        registry.register(skills, auto);
        registry.unregister(skills, auto.id());
        assertTrue(host.calls.contains("unload save=true"));
    }

    @Test
    void givenRegisteredWorld_whenRebuilt_thenVisitsEndedWithRebuildThenWipedAndLoaded() throws Exception {
        registry.register(skills, spec("h1"));
        chunkFile(folder());
        var rebuilt = registry.rebuild(skills, spec("h2"));
        assertEquals(List.of("vystormskills:talentwelt REBUILD"), ended);
        assertTrue(rebuilt.rebuilt());
        assertFalse(Files.exists(folder().resolve("region/r.0.0.mca")));
        assertThrows(ManagedWorldException.class, () -> registry.rebuild(plugin("Other"), spec("h3")));
    }

    @Test
    void givenSameHash_whenReconfigured_thenSpecReplacedWithoutUnloadOrWipe() throws Exception {
        var first = registry.register(skills, spec("h1"));
        chunkFile(folder());
        host.calls.clear();
        WorldSpec changed = WorldSpec.builder("vystormskills:talentwelt", "vystorm_talentwelt", mock(ChunkGenerator.class)).layoutHash("h1").time(6000).build();
        var next = registry.reconfigure(skills, changed);
        assertSame(changed, next.spec());
        assertSame(first.world(), next.world());
        assertSame(next, registry.byWorld(first.world()));
        assertEquals(List.of("detach", "configure", "attach"), host.calls);
        assertTrue(Files.exists(folder().resolve("region/r.0.0.mca")));
        assertThrows(ManagedWorldException.class, () -> registry.reconfigure(skills, spec("h2")), "hash change needs rebuild");
    }

    @Test
    void givenDuplicateIdOrForeignOwner_whenCalled_thenRefused() {
        registry.register(skills, spec("h1"));
        assertEquals(ManagedWorldException.Reason.ID_TAKEN, assertThrows(ManagedWorldException.class, () -> registry.register(skills, spec("h1"))).reason());
        assertThrows(ManagedWorldException.class, () -> registry.unregister(plugin("Other"), "vystormskills:talentwelt"));
        registry.release(skills);
        assertTrue(registry.all().isEmpty());
        assertEquals(List.of("vystormskills:talentwelt " + ExitReason.SHUTDOWN), ended);
    }

    @Test
    void givenFailedUnload_whenRebuilt_thenRegistryAndProtectionRetainedAndNothingWiped() throws Exception {
        var managed = registry.register(skills, spec("h1"));
        chunkFile(folder());host.calls.clear();host.unloadSucceeds = false;
        assertEquals(ManagedWorldException.Reason.BUKKIT_FAILED,
                assertThrows(ManagedWorldException.class, () -> registry.rebuild(skills, spec("h2"))).reason());
        assertSame(managed, registry.byId(managed.id()));
        assertSame(managed, registry.byWorld(managed.world()));
        assertFalse(host.calls.contains("detach"));
        assertEquals("chunks", Files.readString(folder().resolve("region/r.0.0.mca")));
    }

    @Test
    void givenMarkerRemovedOrReplaced_whenRebuilt_thenRefusedBeforeUnloadAndNoDataDeleted() throws Exception {
        var managed = registry.register(skills, spec("h1"));chunkFile(folder());host.calls.clear();
        Files.delete(folder().resolve(WorldFolders.MARKER));
        assertEquals(ManagedWorldException.Reason.FOREIGN_FOLDER,
                assertThrows(ManagedWorldException.class, () -> registry.rebuild(skills, spec("h2"))).reason());
        WorldFolders.writeMarker(folder(), new WorldFolders.Marker("foreign:world", "Other", "h1", false));
        assertEquals(ManagedWorldException.Reason.FOREIGN_FOLDER,
                assertThrows(ManagedWorldException.class, () -> registry.rebuild(skills, spec("h2"))).reason());
        assertSame(managed, registry.byId(managed.id()));
        assertTrue(host.calls.stream().noneMatch(c -> c.startsWith("unload")));
        assertEquals("chunks", Files.readString(folder().resolve("region/r.0.0.mca")));
    }
}
