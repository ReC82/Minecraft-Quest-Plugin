package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot B — les primitives sémantiques produisent-elles des éléments réellement
 * fonctionnels ?
 *
 * <p>Chaque test vérifie ce que le modèle ratait : les deux moitiés d'une porte, les connexions
 * d'une vitre, la trémie d'un escalier, le remplissage d'un pignon. Les états de bloc attendus sont
 * ceux de {@code TestHutBlueprint} et {@code TestWatchtowerBlueprint}, qui fonctionnent en jeu.</p>
 */
class SemanticPrimitivesTest {

    private static Blueprint expand(String yaml) {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("plan illisible : " + problems));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        return r.blueprint();
    }

    private static String at(Blueprint b, int x, int y, int z) {
        return b.at(x, y, z).map(c -> c.state()).orElse(null);
    }

    /** Un volume minimal pour accrocher une primitive : murs sur tout le pourtour. */
    private static String shell(String... extra) {
        StringBuilder sb = new StringBuilder("""
                id: essai
                name: "Essai"
                front: WEST
                size:
                  x: 9
                  y: 8
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 6]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [8, 5, 6]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [7, 5, 5]
                """);
        for (String s : extra) {
            sb.append(s);
        }
        return sb.toString();
    }

    // ---- Porte --------------------------------------------------------------------------------

    @Test
    @DisplayName("La porte pose ses DEUX moitiés, cohérentes, et dégage le passage derrière")
    void thedoorPlacesBothHalvesAndClearsBehind() {
        // front: WEST → la façade avant est le plan x=0. Le modèle ne calcule plus rien.
        Blueprint b = expand(shell("""
                  - op: door
                    facade: FRONT
                    offset: 3
                    block: minecraft:oak_door
                """));

        String lower = at(b, 0, 1, 3);
        String upper = at(b, 0, 2, 3);
        assertTrue(lower != null && lower.contains("half=lower"), "moitié basse : " + lower);
        assertTrue(upper != null && upper.contains("half=upper"), "moitié haute : " + upper);
        // Même orientation et même charnière, sinon la porte s'ouvre de travers.
        assertTrue(lower.contains("facing=west"), lower);
        assertTrue(upper.contains("facing=west"), upper);
        assertTrue(lower.contains("hinge=left") && upper.contains("hinge=left"));
        // La propriété s'appelle « half » — jamais « face », l'erreur du modèle.
        assertFalse(lower.contains("face="), lower);

        // Et le passage est réellement dégagé derrière, sur les deux hauteurs.
        assertEquals(null, at(b, 1, 1, 3), "la cellule intérieure doit être vide");
        assertEquals(null, at(b, 1, 2, 3));
    }

    @Test
    @DisplayName("Le décalage de porte est compté le long de la façade, pas en coordonnées")
    void thedoorOffsetRunsAlongTheFacade() {
        // Façade ouest (x=0) : le décalage court selon Z.
        Blueprint b = expand(shell("""
                  - op: door
                    facade: FRONT
                    offset: 5
                """));

        String door = at(b, 0, 1, 5);
        assertTrue(door != null && door.contains("_door"), "porte attendue en z=5 : " + door);
        // En x=5 il y a un mur, pas une porte : c'est là que le modèle de la V1 avait percé.
        String wrong = at(b, 5, 1, 0);
        assertTrue(wrong == null || !wrong.contains("_door"),
                "aucune porte ne doit se trouver en x=5 — l'erreur de la V1 : " + wrong);
    }

    @Test
    @DisplayName("Une porte sur la façade arrière regarde dans l'autre sens")
    void adoorOnTheBackFacadeLooksTheOtherWay() {
        Blueprint b = expand(shell("""
                  - op: door
                    facade: BACK
                    offset: 3
                """));

        String lower = at(b, 8, 1, 3);
        assertTrue(lower != null, "la façade arrière d'un bâtiment orienté ouest est x=8");
        assertTrue(lower.contains("facing=east"), lower);
    }

    // ---- Fenêtre ------------------------------------------------------------------------------

    @Test
    @DisplayName("La fenêtre perce le mur ET pose un vitrage connecté à l'axe du mur")
    void thewindowCutsTheWallAndConnectsThePane() {
        Blueprint b = expand(shell("""
                  - op: window
                    facade: FRONT
                    offset: 2
                    y: 3
                """));

        String pane = at(b, 0, 3, 2);
        assertTrue(pane != null && pane.startsWith("minecraft:glass_pane"), "vitrage : " + pane);
        // Façade ouest : le mur court selon Z, donc le panneau connecte nord-sud.
        assertTrue(pane.contains("north=true") && pane.contains("south=true"), pane);
        assertTrue(pane.contains("east=false") && pane.contains("west=false"), pane);
    }

    @Test
    @DisplayName("Une série de fenêtres s'écrit en UNE instruction")
    void aseriesOfWindowsTakesASingleInstruction() {
        Blueprint b = expand(shell("""
                  - op: window
                    facade: FRONT
                    offset: 1
                    y: 3
                    count: 3
                    spacing: 2
                """));

        for (int z : new int[] {1, 3, 5}) {
            assertTrue(at(b, 0, 3, z) != null, "fenêtre attendue en z=" + z);
        }
        String between = at(b, 0, 3, 2);
        assertTrue(between == null || !between.contains("glass"),
                "pas de vitrage entre deux fenêtres : " + between);
    }

    @Test
    @DisplayName("Une fenêtre qui sortirait de la façade est ignorée, pas placée de travers")
    void awindowBeyondTheFacadeIsSkipped() {
        Blueprint b = expand(shell("""
                  - op: window
                    facade: FRONT
                    offset: 5
                    y: 3
                    count: 5
                    spacing: 1
                """));

        // La façade ouest compte 7 cellules (z = 0..6) : au-delà, rien n'est posé.
        assertTrue(at(b, 0, 3, 6) != null);
        assertEquals(1, b.blocks().stream()
                .filter(c -> c.state().startsWith("minecraft:glass_pane") && c.z() > 6).count() + 1,
                "aucun vitrage hors de la façade");
    }

    // ---- Éclairage ----------------------------------------------------------------------------

    @Test
    @DisplayName("L'éclairage se répartit, et « hanging » suit la présence d'un support")
    void lightingSpreadsAndHangingFollowsTheSupport() {
        Blueprint b = expand(shell("""
                  - op: box
                    from: [1, 6, 1]
                    to: [7, 6, 5]
                    block: minecraft:dark_oak_planks
                  - op: light
                    from: [2, 5, 2]
                    to: [6, 5, 4]
                    spacing: 2
                """));

        String lantern = at(b, 2, 5, 2);
        assertTrue(lantern != null && lantern.startsWith("minecraft:lantern"), "" + lantern);
        // Il y a un plafond en y=6 : la lanterne doit être suspendue.
        assertTrue(lantern.contains("hanging=true"), lantern);
        assertTrue(BlockSemantics.lightSource(lantern), "et compter comme source pour l'audit");
        // Répartition selon le pas demandé.
        assertTrue(at(b, 4, 5, 2) != null);
        assertEquals(null, at(b, 3, 5, 2), "pas de lanterne entre deux positions");
    }

    @Test
    @DisplayName("Sans support au-dessus, la lanterne est posée et non suspendue")
    void withoutSupportAboveTheLanternSitsOnTheGround() {
        Blueprint b = expand(shell("""
                  - op: light
                    from: [3, 1, 3]
                    to: [3, 1, 3]
                """));

        String lantern = at(b, 3, 1, 3);
        assertTrue(lantern != null && lantern.contains("hanging=false"), "" + lantern);
    }

    // ---- Escalier -----------------------------------------------------------------------------

    @Test
    @DisplayName("L'escalier monte, oriente ses marches, ET perce la trémie — le défaut de la V1")
    void thestaircaseRisesOrientsItsStepsAndCutsTheHatch() {
        Blueprint b = expand(shell("""
                  - op: box
                    from: [1, 4, 1]
                    to: [7, 4, 5]
                    block: minecraft:dark_oak_planks
                  - op: stair_run
                    from: [2, 1, 2]
                    axis: east
                    steps: 3
                    block: minecraft:oak_stairs
                """));

        // Une marche par pas, montant d'un bloc, dans le sens de la montée.
        for (int i = 0; i < 3; i++) {
            String step = at(b, 2 + i, 1 + i, 2);
            assertTrue(step != null && step.contains("facing=east"), "marche " + i + " : " + step);
            assertTrue(step.contains("half=bottom"), step);
            // Hauteur libre au-dessus de chaque marche.
            assertEquals(null, at(b, 2 + i, 2 + i, 2), "hauteur libre au-dessus de la marche " + i);
        }
        // LA trémie : le plancher posé en y=4 doit être percé au débouché.
        assertEquals(null, at(b, 5, 4, 2),
                "le plancher doit être troué au palier — c'est ce que la V1 avait raté");
    }

    // ---- Échelle ------------------------------------------------------------------------------

    @Test
    @DisplayName("L'échelle regarde à l'opposé du mur qui la porte")
    void theladderLooksAwayFromItsWall() {
        Blueprint b = expand(shell("""
                  - op: ladder
                    from: [7, 1, 3]
                    to: [7, 4, 3]
                    facade: BACK
                """));

        String ladder = at(b, 7, 2, 3);
        assertTrue(ladder != null && ladder.startsWith("minecraft:ladder"), "" + ladder);
        // Façade arrière d'un bâtiment orienté ouest = est. L'échelle plaquée dessus regarde l'ouest.
        assertTrue(ladder.contains("facing=west"), ladder);
        assertTrue(BlockSemantics.climbable(ladder), "et être grimpable pour l'audit");
    }

    // ---- Pilastres ----------------------------------------------------------------------------

    @Test
    @DisplayName("Les pilastres donnent du relief pour UNE instruction")
    void pillarsGiveReliefInASingleInstruction() {
        Blueprint b = expand(shell("""
                  - op: pillars
                    from: [0, 1, 0]
                    to: [8, 5, 6]
                    spacing: 4
                    block: minecraft:chiseled_stone_bricks
                """));

        long pilasters = b.blocks().stream()
                .filter(c -> c.state().equals("minecraft:chiseled_stone_bricks")).count();
        assertTrue(pilasters > 20, "les pilastres doivent couvrir plusieurs colonnes : " + pilasters);
        // Les angles sont toujours habillés : ce sont eux qui donnent les arêtes visibles.
        assertEquals("minecraft:chiseled_stone_bricks", at(b, 0, 3, 0));
        assertEquals("minecraft:chiseled_stone_bricks", at(b, 8, 3, 6));
        // Et l'enveloppe gagne un matériau, ce que l'audit mesure.
        assertTrue(b.materials().contains("chiseled_stone_bricks"),
                "l'enveloppe gagne un matériau, ce que l'audit mesure : " + b.materials());
    }

    // ---- Toiture ------------------------------------------------------------------------------

    @Test
    @DisplayName("Le gable corrigé REMPLIT ses pignons — le défaut mesuré à 62 % sur la V1")
    void thefixedGableFillsItsGables() {
        Blueprint b = expand("""
                id: toit
                name: "Toit"
                front: NORTH
                size:
                  x: 9
                  y: 8
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 6]
                    block: minecraft:stone_bricks
                  - op: gable
                    from: [0, 1, 0]
                    to: [8, 4, 6]
                    axis: x
                    block: minecraft:dark_oak_stairs
                    fill: minecraft:stone_bricks
                """);

        // Le faîte court selon X : les pignons sont les faces x=0 et x=8.
        // Sous la pente, les triangles doivent être pleins.
        assertEquals("minecraft:stone_bricks", at(b, 0, 1, 3),
                "le triangle de pignon doit être rempli sous le faîte");
        assertEquals("minecraft:stone_bricks", at(b, 8, 1, 3));
        assertEquals("minecraft:stone_bricks", at(b, 0, 2, 3));
        // Et la pente elle-même est faite d'escaliers orientés vers l'extérieur.
        String eave = at(b, 4, 1, 0);
        assertTrue(eave != null && eave.contains("facing=south"), "avant-toit nord : " + eave);
        String far = at(b, 4, 1, 6);
        assertTrue(far != null && far.contains("facing=north"), "avant-toit sud : " + far);
    }

    @Test
    @DisplayName("Un plan avec les primitives passe l'audit architectural")
    void aplanBuiltWithThePrimitivesPassesTheAudit() {
        String yaml = """
                id: maison_primitives
                name: "Maison en primitives"
                front: WEST
                size:
                  x: 11
                  y: 12
                  z: 9
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [10, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [10, 5, 8]
                    block: minecraft:stone_bricks
                  - op: clear
                    from: [1, 1, 1]
                    to: [9, 5, 7]
                  - op: pillars
                    from: [0, 1, 0]
                    to: [10, 5, 8]
                    spacing: 4
                    block: minecraft:chiseled_stone_bricks
                  - op: door
                    facade: FRONT
                    offset: 4
                    block: minecraft:oak_door
                  - op: window
                    facade: FRONT
                    offset: 2
                    y: 3
                  - op: window
                    facade: BACK
                    offset: 6
                    y: 3
                  - op: light
                    from: [3, 5, 3]
                    to: [7, 5, 5]
                    spacing: 4
                  - op: gable
                    from: [0, 6, 0]
                    to: [10, 10, 8]
                    axis: x
                    block: minecraft:dark_oak_stairs
                    fill: minecraft:stone_bricks
                """;
        List<String> problems = new ArrayList<>();
        Optional<BuildingPlan> plan = BuildingPlanYaml.read(yaml, problems);
        assertTrue(plan.isPresent(), "plan illisible : " + problems);
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan.get());
        assertTrue(expansion.ok(), expansion.error());

        ArchitecturalAudit.Report report = ArchitecturalAudit.audit(
                expansion.blueprint(), plan.get().front(), plan.get().rules());

        assertFalse(report.refused(),
                "un bâtiment construit avec les primitives doit passer : " + report.blockingCodes());
    }
}
