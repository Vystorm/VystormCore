package Vystorm.vystorm_core.mob;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.mythic.MythicMobsBridge;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Regression "Vystorm vor Mythic" für Mob-Referenzen. */
class MobRefsTest {
    private final Plugin mobs = mock(Plugin.class);
    private final MobService service = mock(MobService.class);
    private final Location at = mock(Location.class);
    private final LivingEntity vystormEntity = mock(LivingEntity.class);
    private final Entity mythicEntity = mock(Entity.class);
    private MockedStatic<MythicMobsBridge> mythic;

    @BeforeEach
    void setUp() {
        when(mobs.getName()).thenReturn("VystormMobs");
        when(mobs.isEnabled()).thenReturn(true);
        MobDefinitionView view = mock(MobDefinitionView.class);
        when(service.getDefinition(anyString())).thenReturn(Optional.empty());
        when(service.getDefinition("MM_BLAZE_STANDARD_1")).thenReturn(Optional.of(view));
        MobHandle handle = mock(MobHandle.class);
        when(handle.entity()).thenReturn(Optional.of(vystormEntity));
        when(service.spawn(any())).thenReturn(Optional.of(handle));
        mythic = mockStatic(MythicMobsBridge.class);
        mythic.when(MythicMobsBridge::active).thenReturn(true);
        mythic.when(() -> MythicMobsBridge.exists(anyString())).thenAnswer(call -> !call.getArgument(0).equals("UNKNOWN"));
        mythic.when(() -> MythicMobsBridge.spawn(anyString(), any(), anyInt())).thenReturn(mythicEntity);
    }

    @AfterEach
    void tearDown() {
        mythic.close();
        MobServices.unregister(mobs);
    }

    @Test
    void givenVystormActiveAndKnowsId_whenResolved_thenVystormAndMythicUntouched() {
        MobServices.register(mobs, service);
        assertEquals(Optional.of(MobRefs.Source.VYSTORM), MobRefs.source("mythic:MM_BLAZE_STANDARD_1"));
        assertSame(vystormEntity, MobRefs.spawn("MM_BLAZE_STANDARD_1", at, 5, "test").orElseThrow());
        verify(service).spawn(argThat(request -> request.definitionId().equals("MM_BLAZE_STANDARD_1") && request.level() == 5));
        mythic.verify(() -> MythicMobsBridge.spawn(anyString(), any(), anyInt()), never());
    }

    @Test
    void givenVystormInactive_whenResolved_thenMythicMobsFallback() {
        assertEquals(Optional.of(MobRefs.Source.MYTHIC_MOBS), MobRefs.source("MM_BLAZE_STANDARD_1"));
        assertSame(mythicEntity, MobRefs.spawn("mythic:MM_BLAZE_STANDARD_1", at, 5, "test").orElseThrow());
    }

    @Test
    void givenVystormDoesNotKnowId_whenResolved_thenMythicMobsFallback() {
        MobServices.register(mobs, service);
        assertEquals(Optional.of(MobRefs.Source.MYTHIC_MOBS), MobRefs.source("ForestOgre"));
        assertSame(mythicEntity, MobRefs.spawn("ForestOgre", at, 1, "test").orElseThrow());
        verify(service, never()).spawn(any());
    }

    @Test
    void givenUnknownEverywhere_whenResolved_thenEmpty() {
        MobServices.register(mobs, service);
        assertTrue(MobRefs.source("UNKNOWN").isEmpty());
        mythic.when(() -> MythicMobsBridge.spawn(anyString(), any(), anyInt())).thenReturn(null);
        assertTrue(MobRefs.spawn("UNKNOWN", at, 1, "test").isEmpty());
    }

    @Test
    void givenMythicInactive_whenVystormDoesNotKnowId_thenEmpty() {
        mythic.when(MythicMobsBridge::active).thenReturn(false);
        MobServices.register(mobs, service);
        assertTrue(MobRefs.source("ForestOgre").isEmpty());
        assertTrue(MobRefs.spawn("ForestOgre", at, 1, "test").isEmpty());
    }

    @Test
    void givenVystormPrefix_whenIdUnknown_thenNoMythicFallback() {
        MobServices.register(mobs, service);
        assertTrue(MobRefs.source("vystorm:ForestOgre").isEmpty());
        assertTrue(MobRefs.spawn("vystorm:ForestOgre", at, 1, "test").isEmpty());
        assertEquals("ForestOgre", MobRefs.id(" mythicmobs:ForestOgre "));
        assertEquals("other:x", MobRefs.id("other:x"));
    }

    @Test
    void givenVystormSpawnThrows_whenSpawned_thenUnknownOutcomeAndNoMythicSpawn() {
        MobServices.register(mobs, service);
        when(service.spawn(any())).thenThrow(new IllegalStateException("boom"));
        assertThrows(MobRefs.UnknownOutcomeException.class, () -> MobRefs.spawn("MM_BLAZE_STANDARD_1", at, 1, "test"));
        mythic.verify(() -> MythicMobsBridge.spawn(anyString(), any(), anyInt()), never());
    }
}
