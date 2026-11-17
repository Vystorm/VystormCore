package Vystorm.vystorm_core.worlds;

import static org.junit.jupiter.api.Assertions.*;
import Vystorm.vystorm_core.CoreApi;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class AsyncEntryContractTest {
    @Test void givenAsyncEntry_whenConsumersQueryCapability_thenContractIsDiscoverable() {
        assertTrue(CoreApi.has("async-durable-visits"));
    }
    @Test void givenAsyncEntry_whenDocumented_thenValuablesGuardAndSubmitTimeScalarsAreExplicit() throws Exception {
        String api = Files.readString(Path.of("docs/API.md"));
        assertTrue(api.contains("`OK` means the entry request was accepted"));
        assertTrue(api.contains("2.5 seconds"));
        assertTrue(api.contains("compares only valuables and identity"));
        assertTrue(api.contains("total XP, level, XP progress"));
        assertTrue(api.contains("submit-time scalars"));
        assertTrue(api.contains("a few seconds older than admission"));
    }
}
