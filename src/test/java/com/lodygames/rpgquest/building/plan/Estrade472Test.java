package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Issue #472 — l'estrade d'Elias, refusée trois fois de suite pour {@code PLAN_INVALID} sur son
 * instruction {@code staircase} (16/10/2026, actions 8dfbb7f2, 17f3325c, d355f78c).
 *
 * <p>Les trois plans IA sont recopiés tels quels depuis les brouillons du panel. Ils restent faux
 * — escalier à l'arrière, montant vers l'extérieur, plateau couvrant la rangée des marches — mais
 * pour de vraies raisons, dites clairement. Ce qui ne l'est plus : la primitive refusait
 * <em>toute</em> marche posée sur le terrain (y=0, départ hors du volume, une seule marche), donc
 * aucun plan correct de cette estrade ne pouvait exister.</p>
 */
class Estrade472Test {

    @TempDir
    Path tempDir;

    private BuildingGenerator generator;

    /** L'estrade décrite par le ticket, écrite comme le prompt l'enseigne : marches par façade. */
    static final String ESTRADE = """
            id: estrade_elias
            name: "L'estrade d'Elias"
            kind: decoration
            front: NORTH
            size:
              x: 7
              y: 4
              z: 6
            steps:
              - op: box
                from: [0, 0, 1]
                to: [6, 0, 5]
                block: minecraft:oak_planks
              - op: staircase
                facade: FRONT
                width: 5
                floor: 0
                block: minecraft:oak_stairs
              - op: box
                from: [1, 1, 5]
                to: [5, 1, 5]
                block: minecraft:oak_fence
              - op: box
                from: [0, 1, 5]
                block: minecraft:stripped_oak_log
              - op: box
                from: [6, 1, 5]
                block: minecraft:stripped_oak_log
              - op: light
                from: [0, 2, 5]
                block: minecraft:lantern
              - op: light
                from: [6, 2, 5]
                block: minecraft:lantern
            """;

    @BeforeEach
    void setUp() {
        Path buildings = tempDir.resolve("buildings");
        BuildingLibrary library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        generator = new BuildingGenerator(library,
                new SchematicWorkshop(new BuildingGeneratorTest.FakeGateway(), Logger.getLogger("test")),
                buildings, Logger.getLogger("test"));
    }

    private static Map<String, String> cells(String yaml) {
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(VerticalCirculation282Test.plan(yaml));
        assertTrue(r.ok(), r.error());
        Map<String, String> out = new HashMap<>();
        for (BlueprintBlock b : r.blueprint().blocks()) {
            out.put(b.x() + "/" + b.y() + "/" + b.z(), b.state());
        }
        return out;
    }

    // ---- Le cas du ticket, de bout en bout ------------------------------------------------------

    @Test
    void theEstradeFromTheTicketPassesValidationAuditAndGeneration() {
        BuildingGenerator.Verdict verdict = generator.inspect(ESTRADE);
        assertTrue(verdict.ok(), verdict.message());
        assertEquals(7, verdict.sizeX());
        assertEquals(6, verdict.sizeZ());
        assertTrue(verdict.sizeY() <= 4, "hauteur demandée : 4 au plus");

        BuildingGenerator.Result generated = generator.generate(ESTRADE, false);
        assertTrue(generated.ok(), generated.message());
    }

    @Test
    void theStepsAreCentredOnTheFrontAndFaceTheClimb() {
        Map<String, String> c = cells(ESTRADE);
        // 5 marches centrées sur la façade nord (z=0), x 1 à 5, posées sur le terrain en y=0,
        // tournées vers le sud : on monte en marchant vers le plateau.
        for (int x = 1; x <= 5; x++) {
            String s = c.get(x + "/0/0");
            assertTrue(s != null && s.startsWith("minecraft:oak_stairs[") && s.contains("facing=south")
                    && s.contains("half=bottom"), x + "/0/0 = " + s);
            assertNull(c.get(x + "/1/0"), "rien au-dessus de la marche");
        }
        assertNull(c.get("0/0/0"), "les angles avant restent libres");
        assertNull(c.get("6/0/0"), "les angles avant restent libres");
        // Le plateau 7 × 5 intact derrière, la rambarde et les poteaux à l'arrière.
        for (int x = 0; x <= 6; x++) {
            for (int z = 1; z <= 5; z++) {
                assertEquals("minecraft:oak_planks", c.get(x + "/0/" + z), x + "/0/" + z);
            }
        }
        assertTrue(c.get("3/1/5").startsWith("minecraft:oak_fence"));
        assertEquals("minecraft:stripped_oak_log", BlockStates.bareType(c.get("0/1/5")));
        assertTrue(c.get("0/2/5").startsWith("minecraft:lantern"));
        assertTrue(c.get("6/2/5").startsWith("minecraft:lantern"));
    }

    @Test
    void theSameStepsWrittenWithCoordinatesAreAcceptedToo() {
        String yaml = ESTRADE.replace("""
                    facade: FRONT
                    width: 5
                """, """
                    from: [1, 0, 0]
                    direction: south
                    width: 5
                """);
        BuildingGenerator.Verdict verdict = generator.inspect(yaml);
        assertTrue(verdict.ok(), verdict.message());
        assertEquals(cells(ESTRADE), cells(yaml), "les deux écritures produisent la même estrade");
    }

    @Test
    void aFullPlateauLosesItsFrontRowToTheSteps() {
        // Le modèle a couvert TOUTE l'emprise de planches (7 × 6) : les marches d'entrée prennent
        // la place du bord avant, au lieu d'être refusées comme un mur percé.
        String yaml = ESTRADE.replace("from: [0, 0, 1]", "from: [0, 0, 0]");
        BuildingGenerator.Verdict verdict = generator.inspect(yaml);
        assertTrue(verdict.ok(), verdict.message());
        assertTrue(cells(yaml).get("3/0/0").startsWith("minecraft:oak_stairs["));
        assertEquals("minecraft:oak_planks", cells(yaml).get("0/0/0"));
    }

    @Test
    void theWrittenPlanKeepsTheFacadeForm() {
        String written = BuildingPlanYaml.write(VerticalCirculation282Test.plan(ESTRADE));
        assertTrue(written.contains("facade: FRONT"), written);
        assertTrue(generator.inspect(written).ok(), written);
    }

    // ---- Les rotations de l'entrée -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        // front, taille x, taille z, cellule de la marche centrale, sens de montée
        "NORTH, 7, 6, 3/0/0, south",
        "SOUTH, 7, 6, 3/0/5, north",
        "WEST,  6, 7, 0/0/3, east",
        "EAST,  6, 7, 5/0/3, west",
    })
    void theStepsFollowTheEntranceInEveryOrientation(String front, int sx, int sz, String centre,
                                                     String climb) {
        String yaml = """
                id: estrade_rotation
                name: "Estrade"
                kind: decoration
                front: %s
                size:
                  x: %d
                  y: 3
                  z: %d
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [%d, 0, %d]
                    block: minecraft:oak_planks
                  - op: staircase
                    facade: FRONT
                    width: 5
                    floor: 0
                    block: minecraft:oak_stairs
                """.formatted(front, sx, sz, sx - 1, sz - 1);
        BuildingGenerator.Verdict verdict = generator.inspect(yaml);
        assertTrue(verdict.ok(), front + " : " + verdict.message());
        Map<String, String> c = cells(yaml);
        String step = c.get(centre);
        assertTrue(step != null && step.contains("_stairs[") && step.contains("facing=" + climb),
                front + " " + centre + " = " + step);
        long stairs = c.values().stream().filter(s -> s.contains("_stairs[")).count();
        assertEquals(5, stairs, front + " : cinq marches, pas une de plus");
    }

    // ---- Les trois plans réels : toujours refusés, mais pour la vraie raison -------------------

    @Test
    void realPlan1StillNamesTheCellOutsideTheVolumeWithItsAxes() {
        String m = generator.inspect(VerticalCirculation282Test.fixture("estrade_elias_472_v1.yml")).message();
        assertTrue(m.contains("1/1/6"), m);
        assertTrue(m.contains("x 0..6") && m.contains("z 0..5"), "les bornes par axe : " + m);
    }

    @Test
    void realPlan2IsNoLongerRefusedForStandingOnTheTerrain() {
        BuildingGenerator.Verdict v = generator.inspect(VerticalCirculation282Test.fixture("estrade_elias_472_v2.yml"));
        assertFalse(v.ok());
        assertFalse(v.message().contains("au moins en y=1"), v.message());
        assertFalse(v.message().contains("sous le terrain"), v.message());
    }

    @Test
    void realPlan3ExplainsHowToWriteASingleStep() {
        BuildingGenerator.Verdict v = generator.inspect(VerticalCirculation282Test.fixture("estrade_elias_472_v3.yml"));
        assertFalse(v.ok());
        // floor == y n'est plus une faute : une seule marche. Ce qui reste faux, c'est le départ
        // en l'air hors du volume (marche posée SUR le plateau, à l'arrière).
        assertFalse(v.message().contains("doit être AU-DESSUS"), v.message());
        assertTrue(v.message().contains("facade: FRONT"), v.message());
    }

    // ---- Avec les limites réelles du projet DEV (#305 est passé depuis) ----------------------

    /**
     * « L'estrade d'Elias » sur DEV : max_width 7, max_height 6, max_depth vide (projet créé avant
     * #305), relu en carré — donc 7 en Z. C'est avec ces limites que l'Atelier vérifie le plan.
     */
    static final PlanRepair.Limits PROJECT_LIMITS = new PlanRepair.Limits(7, 6);

    @Test
    void theEstradePassesWithTheLimitsOfTheDevProject() {
        BuildingGenerator.Examination e = generator.preview(ESTRADE, PROJECT_LIMITS);
        assertTrue(e.verdict().ok(), e.verdict().message());
        assertTrue(e.hasPreview(), "l'aperçu est produit");
        assertEquals(7, e.verdict().sizeX());
        assertEquals(6, e.verdict().sizeZ());
    }

    @Test
    void theEstradePassesWithAnExplicitDepthOfSixToo() {
        // Le même projet ressaisi après #305 avec sa vraie emprise : 7 (X) × 6 (Z), hauteur 4.
        BuildingGenerator.Verdict v = generator.inspect(ESTRADE, new PlanRepair.Limits(7, 6, 4));
        assertTrue(v.ok(), v.message());
    }

    @Test
    void theRealPlansAreNeverRefusedForTheProjectLimits() {
        for (int i = 1; i <= 3; i++) {
            BuildingGenerator.Verdict v = generator.inspect(
                    VerticalCirculation282Test.fixture("estrade_elias_472_v" + i + ".yml"), PROJECT_LIMITS);
            assertFalse(v.codes().contains(BuildingGenerator.PROJECT_LIMITS_EXCEEDED), "v" + i + " : " + v.message());
        }
    }

    // ---- Les garde-fous restent ------------------------------------------------------------

    @Test
    void aStepBelowTheTerrainIsStillRefused() {
        String yaml = ESTRADE.replace("steps:\n", "anchor:\n  x: 3\n  y: 1\n  z: 3\nsteps:\n")
                .replace("""
                    facade: FRONT
                    width: 5
                """, """
                    from: [1, 0, 2]
                    direction: south
                    width: 5
                """);
        BuildingGenerator.Verdict v = generator.inspect(yaml);
        assertFalse(v.ok());
        assertTrue(v.message().contains("sous le terrain"), v.message());
    }

    @Test
    void aFloorBelowTheFirstStepIsStillRefused() {
        String yaml = ESTRADE.replace("""
                    facade: FRONT
                    width: 5
                """, """
                    from: [1, 1, 2]
                    direction: south
                    width: 5
                """);
        BuildingGenerator.Verdict v = generator.inspect(yaml);
        assertFalse(v.ok());
        assertTrue(v.message().contains("SOUS la première marche"), v.message());
    }

    @Test
    void anOverWideFlightIsRefusedInsteadOfSilentlyNarrowed() {
        String yaml = ESTRADE.replace("width: 5", "width: " + (Staircase.MAX_WIDTH + 1));
        BuildingGenerator.Verdict v = generator.inspect(yaml);
        assertFalse(v.ok());
        assertTrue(v.message().contains("de 1 à " + Staircase.MAX_WIDTH + " voies"), v.message());
    }

    @Test
    void aFlightWiderThanTheFacadeIsRefusedWithTheCell() {
        String yaml = ESTRADE.replace("width: 5", "width: 9");
        BuildingGenerator.Verdict v = generator.inspect(yaml);
        assertFalse(v.ok());
        assertTrue(v.message().contains("sortent du volume"), v.message());
    }
}
