package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #263 — le dessus d'un toit n'est pas un niveau, mais un bâtiment sans toit reste signalé.
 *
 * <p>Découvert en rendant l'exemple du prompt analysable : sur une petite emprise (9 × 7), chaque
 * rangée d'escaliers d'un toit à deux pentes dépassait le quart de l'emprise et comptait comme un
 * « niveau » — refus pour « niveau inaccessible » et « couverture incomplète » d'un atelier sain.</p>
 */
class AuditRoofLevelsTest {

    private static final String BASE = """
            id: atelier_test
            name: "Atelier"
            kind: habitable
            front: NORTH
            size:
              x: 9
              y: 9
              z: 7
            steps:
              - op: box
                from: [0, 0, 0]
                to: [8, 0, 6]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [8, 4, 6]
                block: minecraft:stone_bricks
              - op: door
                facade: FRONT
                offset: 4
                block: minecraft:oak_door
            """;

    private static final String CEILING_AND_GABLE = """
              - op: box
                from: [0, 5, 0]
                to: [8, 5, 6]
                block: minecraft:oak_planks
              - op: gable
                from: [0, 5, 0]
                to: [8, 8, 6]
                axis: x
                fill: minecraft:stone_bricks
                block: minecraft:oak_stairs
            """;

    private static ArchitecturalAudit.Report audit(String yaml) {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan illisible : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        return ArchitecturalAudit.audit(expansion.blueprint(), plan.front(), plan.rules());
    }

    private static boolean has(ArchitecturalAudit.Report report, String code) {
        return report.blocking().stream().anyMatch(f -> f.code().equals(code));
    }

    @Test
    void theSlopesOfASmallGableRoofAreNotLevels() {
        ArchitecturalAudit.Report report = audit(BASE + CEILING_AND_GABLE);

        assertEquals(1, report.metrics().floors(), "le rez-de-chaussée, et lui seul");
        assertTrue(!has(report, "LEVEL_UNREACHABLE"), report.blocking().toString());
        assertTrue(!has(report, "ROOF_MISSING"), report.blocking().toString());
    }

    @Test
    void aBuildingWithoutAnyRoofIsStillFlagged() {
        ArchitecturalAudit.Report report = audit(BASE);

        assertTrue(has(report, "ROOF_MISSING"), "un bâtiment à ciel ouvert reste refusé : "
                + report.blocking());
    }
}
