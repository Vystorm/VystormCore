package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultDebuggerTest {
    @TempDir Path directory;
    private static final Duration WAIT = Duration.ofSeconds(5);
    private DefaultDebugger debugger(List<DebugLogWriter.Record> output) {
        return new DefaultDebugger(directory, ignored -> {}, output::add);
    }
    private String logs() throws IOException {
        StringBuilder content = new StringBuilder();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".log")).toList()) content.append(Files.readString(file));
        }
        return content.toString();
    }
    @Test void givenExistingMarkersWhenParsedThenUseStableClassKey() {
        assertEquals("DGH", DefaultDebugger.extractPointer(" [DGH 142] Menu missing"));
        assertEquals("Test", DefaultDebugger.extractPointer("[Test/59] Missing player"));
        assertEquals("Module2", DefaultDebugger.extractPointer("[Module2 25] Loaded"));
        assertEquals("DataBaseHandler", DefaultDebugger.extractPointer("[DataBaseHandler] Registered"));
        assertEquals("Unknown", DefaultDebugger.extractPointer("Unmarked text"));
        assertEquals("Unknown", DefaultDebugger.extractPointer(null));
    }
    @Test void givenAllLevelsDisabledWhenLoggingThenNeitherWriteNorDispatch() throws Exception {
        Files.writeString(directory.resolve("states.yml"), "Test:\n  LOW: false\n  MID: false\n  HIGH: false\n  SHUTDOWN: false\n");
        List<DebugLogWriter.Record> output = new CopyOnWriteArrayList<>();
        try (var debugger = debugger(output)) {
            debugger.low("[Test 1] Low", null); debugger.mid("[Test 2] Mid", null);
            debugger.high("[Test 3] High", null); debugger.shutdown("[Test 4] Shutdown", null);
            assertFalse(debugger.highConfirmed("[Test 5] Disabled", null, WAIT));
            debugger.flush(WAIT);
        }
        assertTrue(output.isEmpty()); assertEquals("", logs());
    }
    @Test void givenSameTimestampAndMultilineTextWhenWritingThenRetainSeparateCompleteRecords() throws Exception {
        List<DebugLogWriter.Record> output = new CopyOnWriteArrayList<>();
        try (var debugger = debugger(output)) {
            var now = LocalDateTime.of(2026, 9, 12, 12, 0);
            debugger.mid("[Test 1] First\ncontinued\tvalue", now);
            debugger.mid("[Test 2] Second", now);
            debugger.flush(WAIT);
        }
        assertEquals(2, logs().lines().count()); assertTrue(logs().contains("First\\ncontinued\\tvalue"));
        assertTrue(logs().contains("Second"));
        assertEquals(0, output.size(), "MID bleibt standardmäßig aus der Konsole, steht aber in der Datei");
    }
    @Test void givenConsoleSwitchesWhenLoggingThenOnlyEnabledLevelsReachTheConsole() throws Exception {
        List<DebugLogWriter.Record> output = new CopyOnWriteArrayList<>();
        java.nio.file.Files.writeString(directory.resolve("states.yml"), "_console:\n  MID: true\n");
        try (var debugger = debugger(output)) {
            var now = LocalDateTime.of(2026, 9, 12, 12, 0);
            debugger.low("[Test 1] low", now);
            debugger.mid("[Test 2] mid", now);
            debugger.high("[Test 3] high", now);
            debugger.flush(WAIT);
        }
        assertEquals(3, logs().lines().count(), "die Datei erhält alle Stufen");
        assertEquals(java.util.List.of("MID", "HIGH"), output.stream().map(DebugLogWriter.Record::level).toList());
    }
    @Test void givenLiveStateEditsWhenReloadingThenHonorSwitchAndRetainLastValidSnapshot() throws Exception {
        List<DebugLogWriter.Record> output = new CopyOnWriteArrayList<>();
        try (var debugger = debugger(output)) {
            debugger.mid("[Test 1] Enabled", null); debugger.flush(WAIT);
            Files.writeString(directory.resolve("states.yml"), "Test:\n  MID: false\n"); debugger.reloadStates(WAIT);
            debugger.mid("[Test 2] Disabled", null);
            Files.writeString(directory.resolve("states.yml"), "Test: [invalid yaml");
            assertThrows(IOException.class, () -> debugger.reloadStates(WAIT));
            debugger.mid("[Test 3] Still disabled", null); debugger.low("[New 1] New class", null);
            debugger.flush(WAIT);
            assertEquals("Test: [invalid yaml", Files.readString(directory.resolve("states.yml")));
        }
        assertFalse(logs().contains("Disabled")); assertFalse(logs().contains("Still disabled"));
        assertTrue(logs().contains("Enabled")); assertTrue(logs().contains("New class"));
    }
    @Test void givenInternalOutputLoggingWhenWritingThenPreventRecursion() throws Exception {
        AtomicReference<DefaultDebugger> reference = new AtomicReference<>();
        try (var debugger = new DefaultDebugger(directory, ignored -> {}, record -> reference.get().low("[Nested 1] Recursive", null))) {
            reference.set(debugger);
            debugger.mid("[Test 1] Original", null); debugger.flush(WAIT);
        }
        assertEquals(1, logs().lines().count()); assertFalse(logs().contains("Recursive"));
    }
    @Test void givenBrokenStorageWhenLoggingThenOrdinaryCallsSurviveAndConfirmationReportsFailure() throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "not a directory");
        try (var debugger = new DefaultDebugger(blocked, ignored -> {}, record -> {})) {
            assertDoesNotThrow(() -> debugger.high("[Test 1] Nonessential", null));
            assertThrows(IOException.class, () -> debugger.highConfirmed("[Test 2] Must persist", null, WAIT));
            Files.delete(blocked);
            assertTrue(debugger.highConfirmed("[Test 3] Recovered", null, WAIT));
            assertThrows(IOException.class, () -> debugger.flush(WAIT)); // reports preceding failed records once
            debugger.flush(WAIT);
        }
        try (var files = Files.walk(blocked)) {
            assertTrue(files.filter(p -> p.toString().endsWith(".log")).anyMatch(p -> {
                try { return Files.readString(p).contains("Recovered"); } catch (IOException e) { throw new RuntimeException(e); }
            }));
        }
    }
    @Test void givenShutdownWhenClosingThenDrainOrdinaryMessagesAndRejectFurtherConfirmation() throws Exception {
        var debugger = debugger(new CopyOnWriteArrayList<>());
        for (int i = 0; i < 100; i++) debugger.low("[Test 1] Message " + i, null);
        debugger.close();
        assertEquals(100, logs().lines().count()); assertTrue(Files.readString(directory.resolve("states.yml")).contains("LOW: true"));
        assertThrows(IOException.class, () -> debugger.highConfirmed("[Test 2] After close", null, WAIT));
        debugger.close();
    }
    @Test void givenReplacementWhenOverridingThenCloseOldWriterWithoutClosingSameInstance() {
        var singleton = new Vystorm.vystorm_core.singeltons.DebugSystemSingelton();
        DebugInterface first = mock(DebugInterface.class), second = mock(DebugInterface.class);
        singleton.override(first); singleton.override(first); verify(first, never()).close();
        singleton.override(second); verify(first).close(); assertSame(second, singleton.getDebugger());
        singleton.close(); verify(second).close();
    }
    @Test void givenCustomDebuggerWithoutDurabilityWhenConfirmingThenNeverPretendItWasStored() {
        DebugInterface custom = mock(DebugInterface.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> custom.highConfirmed("text", null, WAIT));
    }
}
