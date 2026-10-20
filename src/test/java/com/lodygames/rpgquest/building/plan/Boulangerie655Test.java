package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.Facing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #655 — « Boulangerie de Central » : les plans réels refusés le 20/10/2026, rejoués hors
 * serveur et sans aucun appel payant. Chaque fixture porte l'identifiant de l'action d'aperçu ou
 * de la version d'où elle vient ; aucune n'a été retouchée.
 *
 * <p>Ce que ces tests fixent : les fautes dont la correction est sûre (façade d'instruction en
 * point cardinal, {@code minecraft:brick}, toit posé au-dessus du volume, marche d'entrée, porte
 * absente de la façade avant) sont réparées par le moteur en UNE passe — l'Atelier ne re-répare
 * pas une version réparée — et la réparation est jugée par l'audit complet, jamais assoupli.</p>
 */
class Boulangerie655Test {

    /** Les bornes réelles du projet 96a24bba : X ≤ 12, Z ≤ 12, Y ≤ 10. */
    private static final PlanRepair.Limits LIMITS = new PlanRepair.Limits(12, 12, 10);

    @TempDir
    Path tempDir;

    private BuildingGenerator generator;

    @BeforeEach
    void setUp() {
        Path buildings = tempDir.resolve("buildings");
        BuildingLibrary library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        generator = new BuildingGenerator(library,
                new SchematicWorkshop(new BuildingGeneratorTest.FakeGateway(), Logger.getLogger("test")),
                buildings, Logger.getLogger("test"));
    }

    private static String fixture(String suffix) {
        return VerticalCirculation282Test.fixture("boulangerie_655_" + suffix + ".yml");
    }

    private BuildingGenerator.Verdict inspect(String yaml) {
        return generator.inspect(yaml, LIMITS);
    }

    /** Le plan réparé, relu et rejugé par le pipeline normal, comme l'Atelier le fait. */
    private BuildingGenerator.Verdict rejudge(BuildingGenerator.Verdict verdict) {
        assertTrue(verdict.repair() != null, "le moteur propose une réparation : " + verdict.message());
        return inspect(verdict.repair().plan());
    }

    // ---- d852629f : « facade: EAST » dans une instruction --------------------------------------

    @Test
    void aCompassFacadeIsTranslatedExactlyFromTheBuildingFrontNotGuessed() {
        assertEquals(Facade.FRONT, BuildingPlanYaml.fromCompass("EAST", Facing.EAST).orElseThrow());
        assertEquals(Facade.BACK, BuildingPlanYaml.fromCompass("west", Facing.EAST).orElseThrow());
        // Vus de l'extérieur, face à la façade EST : la gauche est au SUD, la droite au NORD — l'IA
        // avait écrit l'inverse dans sa correction payante (d54a87b1).
        assertEquals(Facade.LEFT, BuildingPlanYaml.fromCompass("SOUTH", Facing.EAST).orElseThrow());
        assertEquals(Facade.RIGHT, BuildingPlanYaml.fromCompass("NORTH", Facing.EAST).orElseThrow());
        assertEquals(Facade.LEFT, BuildingPlanYaml.fromCompass("EAST", Facing.NORTH).orElseThrow());
        assertTrue(BuildingPlanYaml.fromCompass("UP", Facing.EAST).isEmpty());
    }

    @Test
    void theStrictReadingStillRefusesACompassFacade() {
        List<String> problems = new ArrayList<>();
        assertTrue(BuildingPlanYaml.read(fixture("d852629f"), problems).isEmpty());
        assertTrue(problems.get(0).contains("façade inconnue : « EAST »"), problems.toString());
    }

    @Test
    void d852629fIsRepairedWithoutTheAiFacadeRoofAndBrickInOnePass() {
        BuildingGenerator.Verdict raw = inspect(fixture("d852629f"));
        assertFalse(raw.ok());
        assertEquals("PLAN_INVALID", raw.code());
        BuildingGenerator.Repair repair = raw.repair();
        assertTrue(repair != null, raw.message());
        String changes = String.join("\n", repair.changes());
        assertTrue(changes.contains("Instruction 8 (window) : « facade: EAST »"), changes);
        assertTrue(changes.contains("c'est la façade FRONT"), changes);
        assertTrue(changes.contains("« facade: SOUTH »") && changes.contains("façade LEFT"), changes);
        assertTrue(changes.contains("« facade: NORTH »") && changes.contains("façade RIGHT"), changes);
        assertTrue(changes.contains("minecraft:brick") && changes.contains("minecraft:bricks"), changes);
        assertTrue(changes.contains("Instruction 6 (gable)"), changes);
        // Le « front » du bâtiment n'est pas touché, et la réparation est lisible par la voie stricte.
        assertTrue(repair.plan().contains("front: EAST"), repair.plan());
        BuildingGenerator.Verdict again = rejudge(raw);
        assertFalse(again.codes().contains("PLAN_INVALID"), again.message());
    }

    // ---- d54a87b1 / 73affe8a : toit y=10 hors du volume 0..9, bloc « brick » -------------------

    @Test
    void d54a87b1RoofAboveTheVolumeIsLoweredAndBrickBecomesBricks() {
        BuildingGenerator.Verdict raw = inspect(fixture("d54a87b1"));
        assertEquals("PLAN_INVALID", raw.code());
        assertTrue(raw.message().contains("GABLE") && raw.message().contains("minecraft:brick"), raw.message());
        String changes = String.join("\n", raw.repair().changes());
        assertTrue(changes.contains("abaissé de 1 bloc(s) en 0/9/0 → 11/9/10"), changes);
        assertTrue(changes.contains("« minecraft:bricks »"), changes);
        assertFalse(raw.repair().plan().contains("minecraft:brick\n"), "plus aucun bloc « brick »");
        BuildingGenerator.Verdict again = rejudge(raw);
        assertFalse(again.codes().contains("PLAN_INVALID"), again.message());
    }

    @Test
    void the73affe8aCorrectionThatOnlyShrankXIsRepairedToo() {
        BuildingGenerator.Verdict raw = inspect(fixture("73affe8a"));
        assertTrue(raw.message().contains("0/10/0 → 10/10/10"), raw.message());
        BuildingGenerator.Verdict again = rejudge(raw);
        assertFalse(again.codes().contains("PLAN_INVALID"), again.message());
    }

    @Test
    void aRoofTooFarAboveTheVolumeIsNotMovedSilently() {
        String yaml = fixture("d54a87b1").replace("from: [0, 10, 0]\n    to: [11, 10, 10]",
                "from: [0, 14, 0]\n    to: [11, 14, 10]");
        List<String> problems = new ArrayList<>();
        BuildingPlan raw = BuildingPlanYaml.readUnvalidated(yaml, problems).orElseThrow();
        PlanRepair.Outcome outcome = PlanRepair.repair(raw, LIMITS);
        assertFalse(outcome.repaired(), "au-delà de 3 blocs, c'est une intention à revoir : " + outcome);
    }

    // ---- 14b2493d : NO_ENTRANCE, façade EST (x=9) ----------------------------------------------

    @Test
    void f14b2493dWithoutAnyEntranceGetsARealDoorOnItsFrontAndPassesTheFullAudit() {
        BuildingGenerator.Verdict raw = inspect(fixture("14b2493d"));
        assertEquals("ARCHITECTURE_REFUSED", raw.code());
        assertEquals(List.of("NO_ENTRANCE"), raw.codes());
        String changes = String.join("\n", raw.repair().changes());
        assertTrue(changes.contains("porte") && changes.contains("facade: FRONT"), changes);
        BuildingGenerator.Verdict again = rejudge(raw);
        assertFalse(again.codes().contains("NO_ENTRANCE"), "une entrée traversable existe : " + again.message());
        assertTrue(again.ok(), "le petit bâtiment habitable est conforme : " + again.message());
    }

    @Test
    void aPlanThatAlreadyHasAFrontDoorIsNotGivenASecondOne() {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.readUnvalidated(fixture("14b2493d"), problems).orElseThrow();
        List<PlanStep> steps = new ArrayList<>(plan.steps());
        // Une porte avant posée hors de tout passage utile (dans le pignon) : l'intention est à revoir.
        steps.add(new PlanStep(PlanStep.Kind.DOOR, PlanBox.at(0, 0, 0), "minecraft:oak_door", "",
                Facade.FRONT, java.util.Map.of("offset", 5, "y", 6), ""));
        BuildingPlan withDoor = new BuildingPlan(plan.id(), plan.name(), plan.sizeX(), plan.sizeY(),
                plan.sizeZ(), plan.front(), plan.anchorX(), plan.anchorY(), plan.anchorZ(), steps, plan.kind());
        List<String> changes = new ArrayList<>();
        BuildingPlan out = PlanRepair.doorway(withDoor, changes);
        assertEquals(withDoor, out);
        assertTrue(changes.isEmpty(), changes.toString());
    }

    // ---- 739ea04b : entrée à sauter, ouverture sans porte --------------------------------------

    @Test
    void the739ea04bJumpEntranceMessageHasNoEmptyClause() {
        BuildingGenerator.Verdict raw = inspect(fixture("739ea04b"));
        assertTrue(raw.codes().contains("ENTRANCE_STEP") || raw.codes().contains("NO_ENTRANCE"),
                raw.message());
        assertFalse(raw.message().contains("; ."), raw.message());
    }

    @Test
    void the739ea04bRepairEndsWithAWalkableEntranceOnTheDeclaredFront() {
        BuildingGenerator.Verdict raw = inspect(fixture("739ea04b"));
        BuildingGenerator.Verdict again = rejudge(raw);
        assertFalse(again.codes().contains("ENTRANCE_STEP"), again.message());
        assertFalse(again.codes().contains("NO_ENTRANCE"), again.message());
    }

    // ---- 35ce1e1a : la version conforme de la session « Exigeant » -----------------------------

    @Test
    void theCompliantVersionOfTheRealRunStaysCompliant() {
        BuildingGenerator.Verdict verdict = inspect(fixture("35ce1e1a_conforme"));
        assertTrue(verdict.ok(), verdict.message());
        assertEquals(null, verdict.repair());
    }
}
