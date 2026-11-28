package Vystorm.vystorm_core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.CoreApi;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** With the switch off and no plugin asking, the survey costs nothing and every part answers empty or refuses. */
class WorldSurveyOffTest {
    @AfterEach
    void reset() { WorldSurvey.shutdown(); }

    @Test
    void givenNoSwitchAndNoPlugin_whenThePartsAreUsed_thenTheyAnswerEmptyOrRefuse() {
        WorldSurveyInterface survey = WorldSurveyInterface.DEFAULT;
        assertFalse(survey.active());
        assertFalse(survey.enabled());
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        assertTrue(survey.rooms().cached(world, 0, 0).isEmpty());
        assertTrue(survey.rooms().kindAt(world, 1, 2, 3).isEmpty());
        ExecutionException failed = assertThrows(ExecutionException.class, () -> survey.rooms().request(world, 0, 0).get());
        assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertTrue(failed.getCause().getMessage().contains("survey.enabled"));
        assertThrows(IllegalStateException.class, () -> survey.precompute().start(world, 0, 0, 100, JobOptions.defaults()));
        assertTrue(survey.precompute().jobs().isEmpty());
        assertEquals(0, survey.openCells().tracked(world));
        assertThrows(IllegalStateException.class, () -> survey.listen(mock(Plugin.class), new SurveyListener() {}, true));
        survey.invalidate(world, 0, 0, 0); // nothing to do, no failure
        assertTrue(CoreApi.has(CoreApi.Capability.WORLD_SURVEY));
        assertEquals("world-survey", CoreApi.Capability.WORLD_SURVEY);
        assertEquals("0.68.0", CoreApi.since(CoreApi.Capability.WORLD_SURVEY).orElseThrow());
    }

    @Test
    void givenADisabledPlugin_whenItRequiresTheSurvey_thenItIsRefused() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Probe");
        when(plugin.isEnabled()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> WorldSurvey.require(plugin));
        assertTrue(WorldSurvey.requiredBy().isEmpty());
    }

    @Test
    void givenRuleDefaults_whenRead_thenTheStudyValuesPerEnvironment() {
        assertEquals(new SurveyRule(8, 0, false, true), SurveyRule.OVERWORLD);
        assertEquals(3, SurveyRule.NETHER.hallRadius());
        assertTrue(SurveyRule.END.voidBelow());
        assertEquals(9, SurveyRule.OVERWORLD.margin());
        assertEquals(12, SurveyRule.NETHER.margin());
        assertEquals(SurveyRule.OVERWORLD.hash(), new SurveyRule(8, 0, false, true).hash(), "stable");
        assertThrows(IllegalArgumentException.class, () -> SurveyRule.OVERWORLD.withReach(17));
        assertThrows(IllegalArgumentException.class, () -> SurveyRule.OVERWORLD.withHallRadius(5));
        assertThrows(IllegalArgumentException.class, () -> JobOptions.defaults().withMillisPerTick(0));
    }
}
