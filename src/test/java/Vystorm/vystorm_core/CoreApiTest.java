package Vystorm.vystorm_core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoreApiTest {
    @Test
    void givenBuildVersion_whenQueried_thenRuntimeVersionAndCapabilitiesMatch() {
        assertNotEquals("0.0.0", CoreApi.VERSION, "Version aus vystorm-core.properties");
        assertTrue(CoreApi.atLeast("0.11.0"));
        assertTrue(CoreApi.atLeast("0.10"));
        assertFalse(CoreApi.atLeast("99.0.0"));
        assertTrue(CoreApi.has(CoreApi.Capability.ITEM_SERVICES));
        assertEquals(java.util.Optional.of("0.28.0"), CoreApi.since(CoreApi.Capability.LICENSED_MODULES));
        assertTrue(CoreApi.has(" Resource-Pack "));
        assertFalse(CoreApi.has("does-not-exist"));
        assertFalse(CoreApi.has(null));
        assertEquals("0.9.0", CoreApi.since(CoreApi.Capability.MOB_SERVICES).orElseThrow());
        assertEquals(java.util.Optional.of("0.25.1"), CoreApi.since(CoreApi.Capability.PANELS), "panel API reshaped before the freeze");
        for (String since : CoreApi.capabilities().values()) assertTrue(CoreApi.atLeast(since), since);
    }

    @Test
    void givenVersionStrings_whenCompared_thenNumericPartDecides() {
        assertEquals(0, CoreApi.compare("0.10.0-Vystorm-Core-26.2", "0.10.0"));
        assertTrue(CoreApi.compare("0.10.3-Vystorm-Core-26.2", "0.9.9") > 0);
        assertTrue(CoreApi.compare("0.9.0", "0.10.0") < 0);
        assertEquals(0, CoreApi.compare("garbage", "0.0.0"));
        assertTrue(CoreApi.api().isEmpty(), "ohne Server keine API");
    }
}
