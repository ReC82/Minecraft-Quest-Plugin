package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #282 — la réparation déterministe, rejouée sur les deux plans réels refusés le 12/10/2026,
 * sans aucun appel payant.
 */
class PlanRepair282Test {

    private static BuildingPlan loose(String name) {
        List<String> problems = new ArrayList<>();
        return BuildingPlanYaml.readUnvalidated(VerticalCirculation282Test.fixture(name), problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
    }

    @Test
    void theHouseRoofThatOverflowsByOneBlockIsClampedAndThePlanPasses() {
        // Audit 077e207d : « Instruction 18 (GABLE) : la zone 0/5/0 → 10/8/10 sort du volume
        // 11 × 8 × 11 ». Le projet est plafonné à 8 de haut : le volume ne peut pas grandir.
        BuildingPlan raw = loose("maison_bibliothecaire_282.yml");
        assertTrue(raw.validate().isPresent(), "le plan réel est bien invalide");

        PlanRepair.Outcome outcome = PlanRepair.repair(raw, new PlanRepair.Limits(11, 8));
        assertTrue(outcome.repaired(), outcome.toString());
        assertEquals(List.of("PLAN_INVALID"), outcome.before());
        assertEquals(8, outcome.plan().sizeY(), "le volume reste dans la limite du projet");
        assertTrue(outcome.changes().get(0).contains("Instruction 18 (gable)"), outcome.changes().toString());
        assertTrue(outcome.after().isEmpty(), "le plan réparé passe : " + outcome.after());

        // Relu par le vrai parseur, le plan écrit est identique au plan réparé.
        List<String> problems = new ArrayList<>();
        BuildingPlan reread = BuildingPlanYaml.read(BuildingPlanYaml.write(outcome.plan()), problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
        assertEquals(outcome.plan(), reread);
    }

    @Test
    void whenTheProjectAllowsItTheVolumeGrowsInsteadOfFlatteningTheRoof() {
        PlanRepair.Outcome outcome = PlanRepair.repair(loose("maison_bibliothecaire_282.yml"),
                new PlanRepair.Limits(11, 12));
        assertTrue(outcome.accepted(), outcome.toString());
        assertEquals(9, outcome.plan().sizeY());
        assertTrue(outcome.changes().get(0).startsWith("Volume porté de 11 × 8 × 11 à 11 × 9 × 11"),
                outcome.changes().toString());
    }

    @Test
    void theLibraryGetsAStaircaseLosesItsDeadCirculationAndItsShelvesLineTheWalls() {
        BuildingPlan raw = loose("bibliotheque_hub_282_v3.yml");
        PlanRepair.Outcome outcome = PlanRepair.repair(raw, new PlanRepair.Limits(19, 14));
        assertTrue(outcome.repaired(), outcome.toString());
        assertEquals(List.of("LEVEL_UNREACHABLE"), outcome.before());
        String changes = String.join("\n", outcome.changes());
        assertTrue(changes.contains("Ajout d'un escalier de 6 marche(s)"), changes);
        assertTrue(changes.contains("stair_run"), "la volée trop courte est retirée : " + changes);
        assertTrue(changes.contains("ladder"), "l'échelle trop courte est retirée : " + changes);
        assertTrue(changes.contains("op: walls"), "les bibliothèques longent les murs : " + changes);
        assertTrue(outcome.accepted(), "le plan réparé passe : " + outcome.after() + "\n" + changes);

        String yaml = BuildingPlanYaml.write(outcome.plan());
        assertTrue(yaml.contains("op: staircase"), yaml);
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(outcome.plan());
        var report = ArchitecturalAudit.audit(r.blueprint(), outcome.plan().front(),
                outcome.plan().rules(), r.provenance());
        assertEquals(2, report.metrics().reachableFloors());
        assertFalse(report.refused(), report.blocking().toString());
    }

    @Test
    void aFaultOutsideTheCatalogueIsNotTouched() {
        // Une porte hors façade n'a pas de correction sûre : rien n'est proposé.
        String yaml = """
                id: hors_catalogue
                name: "Hors catalogue"
                front: NORTH
                size: {x: 7, y: 6, z: 7}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [6, 0, 6]
                    block: minecraft:stone
                  - op: door
                    facade: FRONT
                    offset: 40
                """;
        List<String> problems = new ArrayList<>();
        BuildingPlan raw = BuildingPlanYaml.readUnvalidated(yaml, problems).orElseThrow();
        assertFalse(PlanRepair.repair(raw, PlanRepair.Limits.none()).repaired());
    }

    @Test
    void writingThenReadingAPlanIsTheIdentity() {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(
                VerticalCirculation282Test.fixture("bibliotheque_hub_282_v3.yml"), problems).orElseThrow();
        BuildingPlan reread = BuildingPlanYaml.read(BuildingPlanYaml.write(plan), problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
        assertEquals(plan, reread);
    }
}
