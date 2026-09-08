package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.CitizensSpawnPlanner.Action;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Préconditions pures du spawn Citizens (#81 phase 2) : définition, monde, position — sans Bukkit. */
class CitizensSpawnPlannerTest {

    private static final Set<String> WORLDS = Set.of("world_hub", "claims", "wild");

    private static CitizensSpawnPlanner.Plan plan(String world, double x, double y, double z) {
        return CitizensSpawnPlanner.plan("woodcutter_bob", true, true, false, true,
                world, WORLDS, x, y, z, 0f, 0f);
    }

    @Test
    void validDefinitionWorldAndPosition_proceeds() {
        assertEquals(Action.PROCEED, plan("world_hub", 125.5, 64, -82.5).action());
    }

    @Test
    void worldNameIsCaseInsensitive() {
        assertEquals(Action.PROCEED, plan("WORLD_HUB", 0, 64, 0).action());
    }

    @Test
    void citizensUnavailable_isRejected() {
        var p = CitizensSpawnPlanner.plan("woodcutter_bob", true, true, false, false,
                "world_hub", WORLDS, 0, 64, 0, 0f, 0f);
        assertEquals("CITIZENS_UNAVAILABLE", p.code());
    }

    @Test
    void missingDefinition_isRejected() {
        var p = CitizensSpawnPlanner.plan("ghost", false, false, false, true,
                "world_hub", WORLDS, 0, 64, 0, 0f, 0f);
        assertEquals(Action.REJECT, p.action());
        assertEquals("UNKNOWN_NPC", p.code());
    }

    @Test
    void disabledDefinition_isRejected() {
        var p = CitizensSpawnPlanner.plan("woodcutter_bob", true, false, false, true,
                "world_hub", WORLDS, 0, 64, 0, 0f, 0f);
        assertEquals("NPC_DISABLED", p.code());
    }

    @Test
    void alreadyLinkedNpcId_isRejected() {
        var p = CitizensSpawnPlanner.plan("woodcutter_bob", true, true, true, true,
                "world_hub", WORLDS, 0, 64, 0, 0f, 0f);
        assertEquals(Action.REJECT, p.action());
        assertEquals("NPC_ALREADY_LINKED", p.code());
    }

    @Test
    void worldOutsideWhitelist_isRejected() {
        var p = plan("the_nether", 0, 64, 0);
        assertEquals(Action.REJECT, p.action());
        assertEquals("UNKNOWN_WORLD", p.code());
    }

    @Test
    void blankWorld_isRejected() {
        assertEquals("UNKNOWN_WORLD", plan("  ", 0, 64, 0).code());
    }

    @Test
    void nonFiniteCoordinates_areRejected() {
        assertEquals("INVALID_POSITION", plan("world_hub", Double.NaN, 64, 0).code());
        assertEquals("INVALID_POSITION", plan("world_hub", 0, Double.POSITIVE_INFINITY, 0).code());
        assertEquals("INVALID_POSITION", plan("world_hub", 0, 64, Double.NEGATIVE_INFINITY).code());
    }

    @Test
    void coordinatesOutOfBounds_areRejected() {
        assertEquals("INVALID_POSITION", plan("world_hub", 40_000_000, 64, 0).code());
        assertEquals("INVALID_POSITION", plan("world_hub", 0, 64, -40_000_000).code());
        assertEquals("INVALID_POSITION", plan("world_hub", 0, 5000, 0).code());
        assertEquals("INVALID_POSITION", plan("world_hub", 0, -5000, 0).code());
    }

    @Test
    void pitchOutOfRange_isRejected() {
        var p = CitizensSpawnPlanner.plan("woodcutter_bob", true, true, false, true,
                "world_hub", WORLDS, 0, 64, 0, 0f, 120f);
        assertEquals("INVALID_POSITION", p.code());
    }

    @Test
    void nonFiniteYaw_isRejected() {
        var p = CitizensSpawnPlanner.plan("woodcutter_bob", true, true, false, true,
                "world_hub", WORLDS, 0, 64, 0, Float.NaN, 0f);
        assertEquals("INVALID_POSITION", p.code());
    }

    @Test
    void checksAreOrdered_definitionBeforeWorldBeforePosition() {
        // définition absente ET monde invalide ET position absurde -> le premier échec est UNKNOWN_NPC
        var p = CitizensSpawnPlanner.plan("ghost", false, false, false, true,
                "nether", WORLDS, Double.NaN, 99999, 0, 0f, 0f);
        assertEquals("UNKNOWN_NPC", p.code());
    }

    @Test
    void positionErrorHelper_isNullWhenSane() {
        assertEquals(null, CitizensSpawnPlanner.positionError(10, 64, -20, 90f, 12f));
        assertTrue(CitizensSpawnPlanner.positionError(10, 64, -20, 90f, 91f).contains("Pitch"));
    }
}
