package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #282 — le 3<sup>e</sup> plan IA de la Bibliothèque du Hub (action d'audit
 * {@code b88b887b}, 12/10/2026), rejoué sans aucun appel payant.
 *
 * <p>Le plan est recopié tel quel depuis l'action. Ce test fige trois choses : le diagnostic
 * nomme les vraies causes (marche sous le plancher, bibliothèques posées par l'instruction 17),
 * l'audit ne se laisse plus tromper par le dessus des étagères, et la réparation proposée par le
 * moteur ouvre réellement l'étage.</p>
 */
class VerticalCirculation282Test {

    static String fixture(String name) {
        try (InputStream in = VerticalCirculation282Test.class
                .getResourceAsStream("/building/plans/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BuildingPlan plan(String yaml) {
        List<String> problems = new ArrayList<>();
        return BuildingPlanYaml.read(yaml, problems).orElseThrow(
                () -> new AssertionError(String.join(" ", problems)));
    }

    static ArchitecturalAudit.Report audit(BuildingPlan plan) {
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        return ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(), r.provenance());
    }

    static String message(ArchitecturalAudit.Report report, String code) {
        return report.blocking().stream().filter(f -> f.code().equals(code)).findFirst()
                .map(ArchitecturalFinding::message).orElse("");
    }

    @Test
    void theRealPlanStillFailsOnTheUpperFloorWithTheRealFloorsDetected() {
        var report = audit(plan(fixture("bibliotheque_hub_282_v3.yml")));
        assertTrue(report.blockingCodes().contains("LEVEL_UNREACHABLE"), report.blocking().toString());
        // Les vrais planchers : sol en y=0 (on s'y tient en 1), étage en y=6 (on s'y tient en 7).
        // L'ancien audit voyait « y=8 », le dessus des bibliothèques.
        assertEquals(2, report.metrics().floors());
        assertEquals(1, report.metrics().reachableFloors());
        String m = message(report, "LEVEL_UNREACHABLE");
        assertTrue(m.contains("sols en y=1, 7"), m);
    }

    @Test
    void theDiagnosticNamesTheOpeningTheShortFlightAndTheBlockingInstruction() {
        String m = message(audit(plan(fixture("bibliotheque_hub_282_v3.yml"))), "LEVEL_UNREACHABLE");
        // La trémie du plan (x 5–8) prolongée jusqu'en x=10 : le « palier » de stair_run, posé une
        // marche trop bas, a percé le plancher au lieu de s'y poser.
        assertTrue(m.contains("x 5–10, z 5–8"), "la trémie réelle du plan : " + m);
        assertTrue(m.contains("La marche la plus haute est en"), m);
        assertTrue(m.contains("instruction 17 (box minecraft:bookshelf de [2, 7, 2] à [16, 7, 16])"),
                "la cellule qui arrête le joueur et l'instruction qui l'a posée : " + m);
    }

    @Test
    void theEngineProposesAVerifiedStaircaseThatOpensTheUpperFloor() {
        BuildingPlan original = plan(fixture("bibliotheque_hub_282_v3.yml"));
        var report = audit(original);
        assertFalse(report.staircases().isEmpty(), "une réparation doit être trouvée");
        Staircase stair = report.staircases().get(0);
        assertEquals(6, stair.floor());
        assertEquals(1, stair.y());
        assertEquals(6, stair.steps(), "6 marches de y=1 à y=6 — le plan en avait 5");
        assertTrue(message(report, "LEVEL_UNREACHABLE").contains("op: staircase"));

        // Appliquée au plan RÉEL, en passant par le vrai parseur et le vrai expanseur.
        String repaired = fixture("bibliotheque_hub_282_v3.yml").stripTrailing() + "\n"
                + stair.yaml("  ");
        var after = audit(plan(repaired));
        assertFalse(after.blockingCodes().contains("LEVEL_UNREACHABLE"), after.blocking().toString());
        assertEquals(2, after.metrics().reachableFloors());
        // L'étage est atteint, mais 15 × 15 bibliothèques le rendent impraticable : c'est
        // désormais dit, au lieu d'être déclaré conforme.
        assertTrue(after.blockingCodes().contains("LEVEL_CLUTTERED"), after.blocking().toString());
        assertTrue(message(after, "LEVEL_CLUTTERED").contains("instruction 17"));
        // La réparation ne casse ni l'entrée, ni les vitrages, ni l'enveloppe.
        for (String code : List.of("NO_ENTRANCE", "GLAZING_MISSING", "GLAZING_ONE_SIDED",
                "ENVELOPE_OPEN", "ROOF_MISSING")) {
            assertFalse(after.blockingCodes().contains(code), code);
        }
    }

    @Test
    void aStaircaseIsAppliedAfterFurnitureSoLaterBoxesCannotBlockIt() {
        String yaml = """
                id: deux_niveaux
                name: "Deux niveaux"
                front: NORTH
                size: {x: 11, y: 12, z: 11}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 10]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 9, 10]
                    block: minecraft:stone_bricks
                  - op: box
                    from: [0, 5, 0]
                    to: [10, 5, 10]
                    block: minecraft:oak_planks
                  - op: staircase
                    from: [2, 1, 8]
                    direction: east
                    floor: 5
                    width: 2
                    block: minecraft:oak_stairs
                  - op: box
                    from: [1, 1, 8]
                    to: [9, 9, 9]
                    block: minecraft:bookshelf
                  - op: box
                    from: [0, 10, 0]
                    to: [10, 10, 10]
                    block: minecraft:oak_planks
                """;
        BuildingPlan p = plan(yaml);
        var expansion = BuildingPlanExpander.expand(p);
        assertTrue(expansion.ok(), expansion.error());
        AuditGrid grid = AuditGrid.of(expansion.blueprint());
        // La volée tient malgré la box de bibliothèques écrite APRÈS elle.
        for (int i = 0; i < 5; i++) {
            assertTrue(grid.at(2 + i, 1 + i, 8).contains("oak_stairs"), "marche " + i);
        }
        var levels = Circulation.analyse(grid);
        assertEquals(2, levels.levels().size());
        assertEquals(2, levels.reachedCount());
    }

    @Test
    void aStaircaseNeverPiercesTheOuterWall() {
        String yaml = """
                id: mur_perce
                name: "Mur percé"
                front: NORTH
                size: {x: 9, y: 10, z: 9}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [8, 8, 8]
                    block: minecraft:stone_bricks
                  - op: box
                    from: [1, 4, 1]
                    to: [7, 4, 7]
                    block: minecraft:oak_planks
                  - op: staircase
                    from: [4, 1, 4]
                    direction: north
                    floor: 4
                """;
        var expansion = BuildingPlanExpander.expand(plan(yaml));
        assertFalse(expansion.ok());
        assertTrue(expansion.error().contains("mur extérieur"), expansion.error());
    }

    @Test
    void aStaircaseWithoutAFloorAtItsLandingIsRefusedWithTheCell() {
        String yaml = """
                id: sans_palier
                name: "Sans palier"
                front: NORTH
                size: {x: 11, y: 10, z: 11}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 10]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 8, 10]
                    block: minecraft:stone_bricks
                  - op: staircase
                    from: [2, 1, 5]
                    direction: east
                    floor: 4
                """;
        var expansion = BuildingPlanExpander.expand(plan(yaml));
        assertFalse(expansion.ok());
        assertTrue(expansion.error().contains("palier"), expansion.error());
    }

    @Test
    void floorIsRequiredAndMustNotBeBelowTheFirstStep() {
        List<String> problems = new ArrayList<>();
        String base = """
                id: escalier
                name: "Escalier"
                front: NORTH
                size: {x: 9, y: 9, z: 9}
                steps:
                  - op: staircase
                    from: [2, 3, 2]
                    direction: east
                """;
        assertTrue(BuildingPlanYaml.read(base, problems).isEmpty());
        assertTrue(String.join(" ", problems).contains("floor"), problems.toString());
        problems.clear();
        assertTrue(BuildingPlanYaml.read(base + "    floor: 2\n", problems).isEmpty());
        // #472 : « floor » égal au pied est UNE marche ; seul un plancher sous le pied est refusé.
        assertTrue(String.join(" ", problems).contains("SOUS la première marche"), problems.toString());
    }
}
