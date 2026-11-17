package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DebugCrashTest {
    @TempDir Path directory;
    @Test void givenConfirmedRecordWhenJvmIsKilledThenRestartPreservesItAndRepairsTrailingFragment() throws Exception {
        // Windows caps command lines at 32k characters; hand the long classpath to the child through a java @argfile.
        Path argFile = directory.resolve("child.args");
        Files.writeString(argFile, "-cp \"" + test.TestClasspath.property("vystorm.test.classpath").replace('\\', '/') + "\"");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argFile, DebugCrashHarness.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("child-output.txt").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(directory.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(directory.resolve("ready")), () -> {
                try { return Files.readString(directory.resolve("child-output.txt")); } catch (Exception e) { return e.toString(); }
            });
            child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            Path critical;
            try (var files = Files.list(directory.resolve("high-shutdown"))) { critical = files.filter(p -> p.toString().endsWith(".log")).findFirst().orElseThrow(); }
            String confirmed = Files.readString(critical);
            assertTrue(confirmed.contains("confirmed-before-kill")); assertTrue(confirmed.endsWith("\n"));
            // Deterministically model a later interrupted write; an actual kill need not land mid-write.
            Files.writeString(critical, "incomplete-tail", StandardOpenOption.APPEND);
            try (var restarted = new DefaultDebugger(directory, ignored -> {}, record -> {})) {
                assertTrue(restarted.highConfirmed("[CrashTest 3] confirmed-after-restart", null, Duration.ofSeconds(5)));
            }
            assertEquals(confirmed, Files.readString(critical));
            try (var files = Files.list(directory.resolve("high-shutdown"))) {
                assertEquals(2, files.filter(p -> p.toString().endsWith(".log")).count());
            }
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
}
