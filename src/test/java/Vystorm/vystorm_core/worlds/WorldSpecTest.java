package Vystorm.vystorm_core.worlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.junit.jupiter.api.Test;

class WorldSpecTest {
    private final ChunkGenerator generator = mock(ChunkGenerator.class);

    @Test
    void givenInvalidIdOrName_whenBuilt_thenRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldSpec.builder("NoColon", "w", generator));
        assertThrows(IllegalArgumentException.class, () -> WorldSpec.builder("Plugin:Upper", "w", generator));
        assertThrows(IllegalArgumentException.class, () -> WorldSpec.builder("p:x", "bad name", generator));
        assertThrows(NullPointerException.class, () -> WorldSpec.builder("p:x", "w", null));
    }

    @Test
    void givenDefaults_whenBuilt_thenSafeVisitPolicyAndNoProtection() {
        WorldSpec spec = WorldSpec.builder("myplugin:arena", "myplugin_arena", generator).layoutHash(" abc ").build();
        assertEquals("abc", spec.layoutHash());
        assertEquals(Visibility.ALL, spec.visibility());
        assertTrue(spec.protections().isEmpty());
        assertFalse(spec.autoSave());
        VisitPolicy policy = spec.policy();
        assertTrue(policy.allowedCommands().isEmpty(), "no commands unless allowed");
        assertTrue(policy.lockInventory());
        assertEquals(GameMode.ADVENTURE, policy.gameMode());
        assertSame(ToolKit.NONE, policy.toolKit());
        assertSame(VisitHooks.NONE, spec.hooks());
    }

    @Test
    void givenCommandLabels_whenAllowed_thenNormalizedWithoutSlashOrCase() {
        VisitPolicy policy = VisitPolicy.builder().allowCommands("/Talente", "msg", " r ", "", "two words").build();
        assertEquals(Set.of("talente", "msg", "r"), policy.allowedCommands());
    }

    @Test
    void givenProtectAll_whenBuilt_thenEveryProtectionSet() {
        WorldSpec spec = WorldSpec.builder("p:w", "w", generator).protectAll().build();
        for (Protection p : Protection.values()) assertTrue(spec.protects(p), p.name());
        assertEquals(List.of(Protection.EDITS), List.copyOf(WorldSpec.builder("p:w", "w", generator).protect(Protection.EDITS).build().protections()));
    }

    @Test
    void givenReturnFunction_whenNull_thenOriginUsed() {
        Location origin = new Location(mock(World.class), 1, 2, 3);
        VisitPolicy policy = VisitPolicy.builder().returnTo((player, at) -> null).build();
        assertSame(origin, policy.returnLocation(mock(Player.class), origin));
        assertEquals(0, VisitPolicy.builder().cooldownSeconds(-1).build().cooldownSeconds(), "negative cooldown clamps to 0");
    }

    @Test
    void givenRegionSpec_whenCreated_thenLowercaseAndSquare() {
        RegionSpec region = RegionSpec.square("Talentwelt", "Main", 950, 641, Set.of("safe"), null);
        assertEquals("talentwelt", region.kind());
        assertEquals("main", region.id());
        assertEquals(-641, region.minX());
        assertEquals(641, region.maxZ());
        assertTrue(region.flags().isEmpty());
    }
}
