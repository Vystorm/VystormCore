package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Separate JVM so the parent test can simulate an abrupt process crash without a shutdown hook. */
public final class DebugCrashHarness {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var debugger = new DefaultDebugger(directory, System.err::println, record -> {});
        if (!debugger.highConfirmed("[CrashTest 1] confirmed-before-kill", null, Duration.ofSeconds(10))) {
            throw new AssertionError("Critical switch disabled");
        }
        Files.writeString(directory.resolve("ready"), "confirmed");
        debugger.low("[CrashTest 2] unconfirmed-before-kill", null);
        new CountDownLatch(1).await();
    }
}
