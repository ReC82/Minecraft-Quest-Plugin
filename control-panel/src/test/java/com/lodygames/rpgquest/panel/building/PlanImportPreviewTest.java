package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #308 : ce que l'examen avant import lit du plan et compare aux limites du projet. */
class PlanImportPreviewTest {

    private static final String PLAN = "id: bibliotheque_du_hub\nname: \"Bibliothèque du Hub\"\nfront: north\n"
            + "size:\n  x: 21\n  y: 12\n  z: 17\nsteps:\n  - op: box\n";

    @Test
    void theSummaryReadsIdentifierNameFrontAndSize() {
        PlanImportPreview.Summary s = PlanImportPreview.summaryOf(PLAN);

        assertEquals("bibliotheque_du_hub", s.buildingId());
        assertEquals("Bibliothèque du Hub", s.name());
        assertEquals("NORTH", s.front());
        assertEquals(21, s.sizeX());
        assertEquals(12, s.sizeY());
        assertEquals(17, s.sizeZ());
    }

    @Test
    void anUnreadablePlanGivesZerosAndSaysSo() {
        PlanImportPreview p = PlanImportPreview.of(row(), "steps: []", project(30, 20), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertEquals("inconnues", p.dimensions());
        assertTrue(p.conflicts().stream().anyMatch(c -> c.contains("Dimensions illisibles")), p.conflicts().toString());
    }

    @Test
    void conflictsWithTheTargetLimitsAreListed() {
        PlanImportPreview p = PlanImportPreview.of(row(), PLAN, project(19, 10), Optional.empty(), Optional.empty(),
                Optional.empty());

        assertEquals(4, p.conflicts().size(), p.conflicts().toString());
        assertTrue(p.conflicts().get(0).contains("21×17"));
        assertTrue(p.provenance().contains("historique global"));
        assertFalse(p.ownedHere());
    }

    @Test
    void aPlanWithinTheLimitsOfTheSameProjectIsRecognisedAsOwnedHere() {
        BuildingProject target = project(30, 20);
        BuildingPlanDraft owner = new BuildingPlanDraft("d1", "agent", "", PLAN, "", 1, BuildingPlanDraft.Origin.AI_PROPOSAL,
                "", "", "", "", "", "", "", "", 30, 20, false, "", Instant.EPOCH, "", Instant.EPOCH, target.id());
        PlanImportPreview p = PlanImportPreview.of(row(), PLAN.replace("north", "SOUTH")
                .replace("bibliotheque_du_hub", "estrade"), target, Optional.of(owner), Optional.of(target),
                Optional.empty());

        assertTrue(p.conflicts().isEmpty(), p.conflicts().toString());
        assertTrue(p.ownedHere());
    }

    private static AgentActionRow row() {
        return new AgentActionRow("a1", "agent", "building.plan.audit", Map.of(), AgentActionStatus.SUCCESS,
                Instant.EPOCH, "owner", null, 0, null, null, null, null, null);
    }

    private static BuildingProject project(int width, int height) {
        return new BuildingProject("p1", "agent", "Estrade", "brief", "estrade", "SOUTH", width, height, "", 1,
                false, "", Instant.EPOCH, "", Instant.EPOCH);
    }
}
