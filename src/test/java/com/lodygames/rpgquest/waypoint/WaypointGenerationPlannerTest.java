package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #124 : couvre {@link WaypointGenerationPlanner} — jamais au pied du joueur, déterministe. */
class WaypointGenerationPlannerTest {

    private final WaypointGenerationPlanner planner = new WaypointGenerationPlanner();

    @Test
    void everyCandidateSitsInsideTheConfiguredRing() {
        int min = 24;
        int max = 72;
        List<WaypointGenerationPlanner.Candidate> candidates = planner.candidates(1000, -500, 42L, min, max, 40);
        assertEquals(40, candidates.size());
        for (WaypointGenerationPlanner.Candidate c : candidates) {
            double distance = Math.hypot(c.blockX() - 1000.0, c.blockZ() - (-500.0));
            assertTrue(distance >= min - 2, "jamais au pied du joueur : " + distance);
            assertTrue(distance <= max + 2, "jamais au-delà du rayon max : " + distance);
        }
    }

    @Test
    void sameSeedProducesTheExactSameOrderedCandidates() {
        List<WaypointGenerationPlanner.Candidate> a = planner.candidates(0, 0, 12345L, 20, 60, 12);
        List<WaypointGenerationPlanner.Candidate> b = planner.candidates(0, 0, 12345L, 20, 60, 12);
        assertEquals(a, b, "un retry plus tard repart de la même liste — aucun scan aléatoire par déplacement");
    }

    @Test
    void differentSeedsProduceDifferentCandidates() {
        List<WaypointGenerationPlanner.Candidate> a = planner.candidates(0, 0, 1L, 20, 60, 12);
        List<WaypointGenerationPlanner.Candidate> b = planner.candidates(0, 0, 2L, 20, 60, 12);
        assertTrue(!a.equals(b));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> planner.candidates(0, 0, 0L, 0, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> planner.candidates(0, 0, 0L, 30, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> planner.candidates(0, 0, 0L, 10, 20, 0));
    }
}
