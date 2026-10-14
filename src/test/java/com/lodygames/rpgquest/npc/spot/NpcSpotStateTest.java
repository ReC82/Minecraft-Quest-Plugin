package com.lodygames.rpgquest.npc.spot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.npc.spot.NpcSpotState.Position;
import com.lodygames.rpgquest.npc.spot.NpcSpotState.Status;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Issue #381 — l'état constaté d'un emplacement, décidé sans serveur. */
class NpcSpotStateTest {

    private static final Instant T = Instant.parse("2026-10-15T00:00:00Z");

    private static NpcSpot spot(String npcId) {
        return new NpcSpot("npcspot_0001", "A", "world", 10.5, 64, 10.5, 0f, 0f, 2, npcId, "Lody", T, T);
    }

    @Test
    void anUnusableWorldWinsOverEverythingElse() {
        assertEquals(Status.INACCESSIBLE, NpcSpotState.classify(spot("guard"), false, true,
                new Position("world", 10.5, 64, 10.5), false).status());
    }

    @Test
    void aFreeSpotIsFreeUnlessSomeoneElseStandsOnIt() {
        assertEquals(Status.FREE, NpcSpotState.classify(spot(null), true, true, null, false).status());
        assertEquals(Status.CONFLICT, NpcSpotState.classify(spot(null), true, true, null, true).status());
    }

    @Test
    void anAssignedNpcWithinTheRadiusOccupiesTheSpot() {
        assertEquals(Status.OCCUPIED, NpcSpotState.classify(spot("guard"), true, true,
                new Position("world", 12.0, 64, 10.5), false).status());
    }

    @Test
    void anAssignedNpcThatWanderedOffOrChangedWorldIsAConflict() {
        assertEquals(Status.CONFLICT, NpcSpotState.classify(spot("guard"), true, true,
                new Position("world", 30, 64, 10.5), false).status());
        assertEquals(Status.CONFLICT, NpcSpotState.classify(spot("guard"), true, true,
                new Position("hub", 10.5, 64, 10.5), false).status());
    }

    @Test
    void anAssignedNpcMissingInGameOrWithoutDefinitionIsAConflict() {
        assertEquals(Status.CONFLICT, NpcSpotState.classify(spot("guard"), true, true, null, false).status());
        assertEquals(Status.CONFLICT, NpcSpotState.classify(spot("guard"), true, false,
                new Position("world", 10.5, 64, 10.5), false).status());
    }
}
