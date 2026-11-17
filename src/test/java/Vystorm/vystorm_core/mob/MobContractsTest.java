package Vystorm.vystorm_core.mob;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MobContractsTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), P = UUID.randomUUID();

    @Test
    void givenHostileMobAndPlayer_whenFallback_thenHostileBothWays() {
        EntityRef mob = EntityRef.mob(A, "ORC_CLANS", null, null, Set.of());
        EntityRef player = EntityRef.player(P);
        assertEquals(Relation.HOSTILE, RelationServices.fallback(mob, player));
        assertEquals(Relation.HOSTILE, RelationServices.fallback(player, mob));
    }

    @Test
    void givenSameFactionOrTeam_whenFallback_thenAlly() {
        EntityRef orcA = EntityRef.mob(A, "ORC_CLANS", null, null, Set.of());
        EntityRef orcB = EntityRef.mob(B, "ORC_CLANS", null, null, Set.of());
        assertEquals(Relation.ALLY, RelationServices.fallback(orcA, orcB));
        EntityRef red = EntityRef.mob(A, "ORC", "RED", null, Set.of());
        EntityRef redPlayer = new EntityRef(P, EntityRef.Kind.PLAYER, null, "RED", null, Set.of());
        assertEquals(Relation.ALLY, RelationServices.fallback(red, redPlayer));
    }

    @Test
    void givenDifferentFactions_whenFallback_thenNeutralUntilDeclared_andOwnedMobAlliedToOwner() {
        EntityRef orc = EntityRef.mob(A, "ORC", null, null, Set.of());
        EntityRef undead = EntityRef.mob(B, "UNDEAD", null, null, Set.of());
        assertEquals(Relation.NEUTRAL, RelationServices.fallback(orc, undead));
        EntityRef summon = EntityRef.mob(B, null, null, P, Set.of());
        assertEquals(Relation.ALLY, RelationServices.fallback(summon, EntityRef.player(P)));
    }

    @Test
    void givenSameFactionDifferentArenaTeams_whenFallback_thenHostile() {
        EntityRef red = EntityRef.mob(A, "ORC", "RED", null, Set.of());
        EntityRef blue = EntityRef.mob(B, "ORC", "BLUE", null, Set.of());
        assertEquals(Relation.HOSTILE, RelationServices.fallback(red, blue));
    }

    @Test
    void givenTagQuery_whenMatching_thenFiltersByFactionRoleThreatTier() {
        MobDefinitionView archer = new MobDefinitionView("ORC_ARCHER", "Orc Archer", "SKELETON", "ORC", "ORC", "RANGED_KITER",
                "RANGED_KITER", MobTier.NORMAL, 25, Set.of("ORC"), Set.of("RANGED"), Set.of("FOREST"), List.of(), List.of(), "native", false);
        MobQuery query = MobQuery.builder().faction("orc").role("ranged").environment("forest").threat(20, 35).tiers(MobTier.NORMAL, MobTier.ELITE).build();
        assertTrue(query.matches(archer));
        assertFalse(MobQuery.builder().threat(30, 40).build().matches(archer));
        assertFalse(MobQuery.builder().role("MAGE").build().matches(archer));
    }

    @Test
    void givenCityAndSiegeLayers_whenCombined_thenMostRestrictiveRulesAndHighestAi() {
        ContextLayer city = new ContextLayer(ContextKind.CITY, "city:1", "cities", 400, Set.of(), Map.of("disposition", "HOSTILE"),
                CombatRules.PROTECTED, new AiRules(0.5, true, false, 0, true), List.of());
        ContextLayer siege = new ContextLayer(ContextKind.EVENT, "siege:1", "events", 500, Set.of("SIEGE"), Map.of(),
                CombatRules.siege(Set.of("GATE")), new AiRules(1.5, false, true, 0, false),
                List.of(Objective.entity("gate", ObjectiveType.DESTROY, B, 10)));
        MobContext context = new MobContext(List.of(city, siege), CombatRules.OPEN_WORLD);
        assertFalse(context.combatRules().damagePlayers());
        assertFalse(context.combatRules().mayBreak("GATE"));
        assertEquals(1.5, context.aiRules().aggressionMultiplier());
        assertTrue(context.tags().contains("SIEGE"));
        assertEquals("gate", context.objectives().getFirst().id());
    }

    @Test
    void givenSiegeOnly_whenBlockTarget_thenOnlyWhitelistedBlocksBreakable() {
        CombatRules siege = CombatRules.siege(Set.of("CITY_WALL", "GATE"));
        assertTrue(siege.mayBreak("GATE"));
        assertFalse(siege.mayBreak("PRIVATE_HOUSE"));
        assertFalse(CombatRules.OPEN_WORLD.mayBreak("GATE"));
    }
}
