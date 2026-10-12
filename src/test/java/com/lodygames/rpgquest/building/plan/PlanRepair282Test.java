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
        return looseYaml(VerticalCirculation282Test.fixture(name));
    }

    private static BuildingPlan looseYaml(String yaml) {
        List<String> problems = new ArrayList<>();
        return BuildingPlanYaml.readUnvalidated(yaml, problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
    }

    /**
     * La maison réelle, dont le « lit » {@code minecraft:bed} — bloc inexistant, refusé depuis
     * #288–#292 — est remplacé par de la laine : ces tests portent sur le TOIT, et le lit a son
     * propre test ci-dessous. Le moteur, lui, ne fait jamais ce remplacement.
     */
    private static BuildingPlan houseWithoutTheUnknownBed() {
        String yaml = VerticalCirculation282Test.fixture("maison_bibliothecaire_282.yml");
        assertTrue(yaml.contains("block: minecraft:bed\n"), "le plan réel contient bien le lit fautif");
        return looseYaml(yaml.replace("block: minecraft:bed\n", "block: minecraft:white_wool\n"));
    }

    @Test
    void theRealHousePlanIsRefusedForItsUnknownBedAndTheEngineDoesNotGuessAColour() {
        BuildingPlan raw = loose("maison_bibliothecaire_282.yml");
        String refusal = raw.validate().orElseThrow();
        assertTrue(refusal.contains("minecraft:bed"), refusal);
        assertTrue(refusal.contains("instruction 17"), refusal);
        assertTrue(refusal.contains("minecraft:pink_bed"), "les identifiants réels sont proposés : " + refusal);
        PlanRepair.Outcome outcome = PlanRepair.repair(raw, new PlanRepair.Limits(11, 8));
        assertFalse(outcome.repaired(), "aucune couleur n'est inventée : " + outcome);
    }

    @Test
    void theHouseRoofThatOverflowsByOneBlockIsClampedAndThePlanPasses() {
        // Audit 077e207d : « Instruction 18 (GABLE) : la zone 0/5/0 → 10/8/10 sort du volume
        // 11 × 8 × 11 ». Le projet est plafonné à 8 de haut : le volume ne peut pas grandir.
        BuildingPlan raw = houseWithoutTheUnknownBed();
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
        PlanRepair.Outcome outcome = PlanRepair.repair(houseWithoutTheUnknownBed(),
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
        // #291 : le seuil, un bloc au-dessus du terrain sans marche, est désormais vu aussi.
        assertEquals(List.of("ENTRANCE_STEP", "LEVEL_UNREACHABLE"), outcome.before());
        String changes = String.join("\n", outcome.changes());
        assertTrue(changes.contains("Ajout d'un escalier de 6 marche(s)"), changes);
        assertTrue(changes.contains("stair_run"), "la volée trop courte est retirée : " + changes);
        assertTrue(changes.contains("ladder"), "l'échelle trop courte est retirée : " + changes);
        assertTrue(changes.contains("op: walls"), "les bibliothèques longent les murs : " + changes);
        // Façade sur la limite du volume : pas de place pour des marches, la fondation est enterrée.
        assertTrue(changes.contains("fondation enterrée"), changes);
        assertEquals(1, outcome.plan().anchorY());
        assertTrue(outcome.accepted(), "le plan réparé passe : " + outcome.after() + "\n" + changes);

        String yaml = BuildingPlanYaml.write(outcome.plan());
        assertTrue(yaml.contains("op: staircase"), yaml);
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(outcome.plan());
        var report = ArchitecturalAudit.audit(r.blueprint(), outcome.plan().front(),
                outcome.plan().rules(), r.provenance(), outcome.plan().anchorY());
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
