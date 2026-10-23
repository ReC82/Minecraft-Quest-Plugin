package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #747 — « Église du hub » : les plans réels du 22/10/2026 (projet d770e76f), rejoués hors
 * serveur et sans aucun appel payant. Aucune fixture n'a été retouchée : chacune porte l'identifiant
 * court de la version d'où elle vient.
 *
 * <p>Deux causes confirmées par ce rejeu. (1) Un « clear [7,1,1] → [11,12,17] » placé APRÈS la porte,
 * les fenêtres et les lumières effaçait la porte et tout le grand vitrail arrière, et perçait les
 * murs avant et arrière sur toute leur hauteur — sans qu'aucun diagnostic ne le nomme. (2) Le contrôle
 * d'enveloppe lisait les faces du VOLUME : sous un toit qui déborde (0 à 18) d'un mur en retrait (1 à
 * 17), l'avant-toit comptait comme 504 « ouvertures », attribuées aux pignons ; le modèle a répondu
 * en empilant un second toit (b4d37c6c : 630 ouvertures et un faux étage).</p>
 */
class Eglise747Test {

    private static final PlanRepair.Limits LIMITS = new PlanRepair.Limits(19, 19, 23);

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

    private BuildingGenerator.Verdict inspect(String version) {
        return generator.inspect(VerticalCirculation282Test.fixture("eglise_747_" + version + ".yml"), LIMITS);
    }

    @Test
    void theOverhangingEavesAreNoLongerCountedAsOpeningsOnlyTheClearedHolesAre() {
        BuildingGenerator.Verdict v = inspect("8a2a9614");

        assertTrue(v.codes().contains("ENVELOPE_OPEN"), v.message());
        // Avant #747 : 504 cellules, toutes attribuées aux pignons. Restent les deux trous réels.
        assertEquals(72, v.metrics().openEnvelopeCellsHigh(), v.message());
        assertTrue(v.message().contains("face NORD, x 7–11") && v.message().contains("face SUD, x 7–11"),
                v.message());
        assertTrue(v.message().contains("vidées par l'instruction 20 (clear de [7, 1, 1] à [11, 12, 17])"),
                v.message());
        assertFalse(v.message().contains("PIGNON non rempli"), "aucun pignon n'est en cause ici : " + v.message());
        assertTrue(v.message().contains("Ne superpose PAS un second toit"), v.message());
    }

    @Test
    void theClearThatErasesTheDoorAndTheGreatWindowIsNamed() {
        BuildingGenerator.Verdict v = inspect("8a2a9614");

        assertTrue(v.codes().contains("CLEAR_ERASES"), v.message());
        assertTrue(v.message().contains("2/2 cellule(s) de l'instruction 4 (door minecraft:dark_oak_door façade FRONT)"),
                v.message());
        assertTrue(v.message().contains("18/18 cellule(s) de l'instruction 9 (window "
                + "minecraft:red_stained_glass_pane façade BACK)"), v.message());
    }

    @Test
    void theStackedSecondRoofIsReportedAsItsOwnUnfilledGable() {
        BuildingGenerator.Verdict v = inspect("b4d37c6c");

        assertTrue(v.codes().contains("ENVELOPE_OPEN"), v.message());
        assertTrue(v.message().contains("sous le toit de l'instruction 24 (gable"), v.message());
        assertTrue(v.message().contains("PIGNON non rempli"), v.message());
    }

    @Test
    void everyReplayedRefusalIsReproducedWithoutAnyPaidCall() {
        // Les verdicts du journal, rejoués : mêmes familles de refus, plus la cause enfin nommée.
        assertTrue(inspect("7686a75f").codes().contains("PLAN_INVALID"));
        assertTrue(inspect("804a02d9").codes().contains("PLAN_INVALID"));
        assertTrue(inspect("8663160f").codes().contains("PLAN_INVALID"));
        BuildingGenerator.Verdict lanterns = inspect("6b19e53c");
        assertTrue(lanterns.codes().contains("UNSUPPORTED_BLOCK"), lanterns.message());
        assertTrue(lanterns.codes().contains("CLEAR_ERASES"), lanterns.message());
    }

    @Test
    void thePaletteOfTheCompiledResultIsMeasured() {
        BuildingGenerator.Verdict v = inspect("8a2a9614");

        // Le pupitre et l'autel en quartz posés en [7..11, 1..2, 8] survivent au « clear » (posés après).
        assertTrue(v.metrics().palette().getOrDefault("minecraft:lectern", 0) > 0, v.metrics().palette().toString());
        // Le grand vitrail rouge, lui, a disparu du résultat — c'est ce que le brief perdait sans le dire.
        assertEquals(0, v.metrics().palette().getOrDefault("minecraft:red_stained_glass_pane", 0),
                v.metrics().palette().toString());
        assertFalse(v.metrics().palette().containsKey("minecraft:bell"), "aucune cloche dans ce plan");
    }

    private static final String INSET_CHAPEL = """
            id: chapelle_test
            name: "Chapelle"
            kind: habitable
            front: SOUTH
            size:
              x: 11
              y: 11
              z: 11
            steps:
              - op: box
                from: [0, 0, 0]
                to: [10, 0, 10]
                block: minecraft:stone_bricks
              - op: walls
                from: [1, 1, 1]
                to: [9, 6, 9]
                block: minecraft:white_concrete
            %s
              - op: box
                from: [1, 6, 1]
                to: [9, 6, 9]
                block: minecraft:oak_planks
              - op: gable
                from: [0, 7, 0]
                to: [10, 10, 10]
                axis: x
                fill: minecraft:white_concrete
                block: minecraft:deepslate_tile_stairs
              - op: door
                facade: FRONT
                offset: 5
                block: minecraft:oak_door
            """;

    private static ArchitecturalAudit.Report audit(String yaml) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan illisible : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        return ArchitecturalAudit.audit(expansion.blueprint(), plan.front(), plan.rules(), expansion.provenance(),
                plan.anchorY());
    }

    @Test
    void aWallSetBackUnderAnOverhangingRoofIsClosed() {
        ArchitecturalAudit.Report report = audit(INSET_CHAPEL.formatted(""));

        assertEquals(0, report.metrics().openEnvelopeCellsHigh(), report.blocking().toString());
        assertFalse(report.blockingCodes().contains("ENVELOPE_OPEN"), report.blocking().toString());
    }

    @Test
    void aHoleCutThroughThatSameWallIsStillAnOpeningWithItsCause() {
        ArchitecturalAudit.Report report = audit(INSET_CHAPEL.formatted("""
                  - op: clear
                    from: [4, 4, 9]
                    to: [6, 5, 9]
                """));

        assertTrue(report.blockingCodes().contains("ENVELOPE_OPEN"), report.blocking().toString());
        String message = report.blocking().stream().filter(f -> f.code().equals("ENVELOPE_OPEN")).findFirst()
                .orElseThrow().message();
        assertTrue(message.contains("face SUD") && message.contains("vidées par l'instruction 3 (clear"), message);
    }

    @Test
    void aClearPlacedAfterTheDoorRefusesThePlanAndNamesTheDoor() {
        String yaml = INSET_CHAPEL.formatted("") + """
                  - op: clear
                    from: [4, 1, 8]
                    to: [6, 2, 9]
                """;
        ArchitecturalAudit.Report report = audit(yaml);

        assertTrue(report.blockingCodes().contains("CLEAR_ERASES"), report.blocking().toString());
    }

    // ---- 23/10/2026 : V-2caffe63, « marches d'entrée » refusées comme un escalier intérieur --------

    @Test
    void entryStepsAgainstAFacadeOnTheVolumeEdgeAreRefusedWithTheEntranceDiagnostic() {
        BuildingGenerator.Verdict v = inspect("2caffe63");

        assertTrue(v.codes().contains("PLAN_INVALID"), v.message());
        // La géométrie reste impossible (la marche irait sous la porte, dans le mur de façade z=18) ;
        // le diagnostic dit désormais pourquoi et les deux façons de faire, sans parler d'escalier intérieur.
        assertTrue(v.message().contains("Instruction 5 (STAIRCASE) : marches d'entrée (façade FRONT)"), v.message());
        assertTrue(v.message().contains("z=18") && v.message().contains("« anchor: y: 1 »"), v.message());
        assertFalse(v.message().contains("entièrement à l'intérieur"), v.message());
        // La réparation sûre du moteur est inchangée : marches inutiles retirées, entrée de plain-pied.
        assertTrue(v.repair() != null && v.repair().changes().stream()
                .anyMatch(c -> c.startsWith("Retrait de l'instruction 5 (staircase")), String.valueOf(v.repair()));
        assertTrue(v.repair().plan().contains("anchor:\n  x: 8\n  y: 1"), v.repair().plan());
    }

    @Test
    void entryStepsInFrontOfASetBackFacadeAreStillAccepted() {
        // Même église, corps en retrait d'une rangée : la rangée z=18 est libre pour la marche.
        String yaml = """
                id: chapelle_perron
                name: "Chapelle"
                kind: habitable
                front: SOUTH
                size:
                  x: 11
                  y: 11
                  z: 12
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 10]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 6, 10]
                    block: minecraft:white_concrete
                  - op: box
                    from: [0, 6, 0]
                    to: [10, 6, 10]
                    block: minecraft:oak_planks
                  - op: door
                    facade: FRONT
                    offset: 5
                    block: minecraft:oak_door
                  - op: staircase
                    facade: FRONT
                    offset: 5
                    width: 3
                    floor: 0
                    block: minecraft:stone_brick_stairs
                """;
        java.util.List<String> problems = new java.util.ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems).orElseThrow(() -> new AssertionError(problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);

        assertTrue(expansion.ok(), expansion.error());
        assertTrue(expansion.blueprint().blocks().stream().anyMatch(b -> b.z() == 11 && b.y() == 0
                && b.state().startsWith("minecraft:stone_brick_stairs[facing=north")), "marche en z=11");
        // Le palier est le seuil : la porte reste, et l'on entre depuis le terrain sans sauter.
        assertTrue(expansion.blueprint().blocks().stream().anyMatch(b -> b.x() == 5 && b.y() == 1 && b.z() == 10
                && b.state().contains("_door[")), "porte conservée");
        assertFalse(audit(yaml).blockingCodes().contains("NO_ENTRANCE"), audit(yaml).blocking().toString());
    }

    // ---- Les cloches : attache calculée sur la grille finale -----------------------------------------

    private static BuildingPlanExpander.Result expandBell(String extra) {
        String yaml = """
                id: clocher_test
                name: "Clocher"
                kind: decoration
                front: SOUTH
                size:
                  x: 5
                  y: 8
                  z: 5
                steps:
                  - op: walls
                    from: [0, 0, 0]
                    to: [4, 4, 4]
                    block: minecraft:stone_bricks
                  - op: box
                    from: [0, 5, 0]
                    to: [4, 5, 4]
                    block: minecraft:dark_oak_planks
                %s""".formatted(extra);
        java.util.List<String> problems = new java.util.ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems).orElseThrow(() -> new AssertionError(problems));
        return BuildingPlanExpander.expand(plan);
    }

    @Test
    void aBellUnderABeamHangsFromItInsteadOfStandingOnAir() {
        BuildingPlanExpander.Result r = expandBell("""
                  - op: box
                    from: [2, 4, 2]
                    block: minecraft:bell
                """);

        assertTrue(r.ok(), r.error());
        String bell = r.blueprint().blocks().stream().filter(b -> b.x() == 2 && b.y() == 4 && b.z() == 2)
                .findFirst().orElseThrow().state();
        assertEquals("minecraft:bell[attachment=ceiling,facing=north,powered=false]", bell);
        AuditGrid grid = AuditGrid.of(r.blueprint());
        assertTrue(Stability.unsupported(grid).isEmpty(), Stability.unsupported(grid).toString());
    }

    @Test
    void aFloatingBellIsNamedAsUnsupported() {
        BuildingPlanExpander.Result r = expandBell("""
                  - op: box
                    from: [2, 7, 2]
                    block: minecraft:bell
                """);

        assertTrue(r.ok(), r.error());
        var problems = Stability.unsupported(AuditGrid.of(r.blueprint()));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).why().contains("cloche"), problems.toString());
    }
}
