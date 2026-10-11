package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94 — l'expansion d'un plan en blocs : le moteur déterministe entre l'IA et le fichier.
 *
 * <p>Ces tests sont le filet qui rend la génération par IA acceptable. Le modèle écrit une
 * intention ; tout ce qui se passe ensuite est vérifié ici, sans serveur, sans WorldEdit, et sans
 * dépendre de ce qu'un modèle aura produit ce jour-là.</p>
 */
class BuildingPlanExpanderTest {

    private static BuildingPlan plan(List<PlanStep> steps) {
        return new BuildingPlan("hub_library", "Bibliothèque du Hub", 7, 6, 5, Facing.WEST,
                3, 0, 2, steps);
    }

    // ---- Les primitives ----------------------------------------------------------------------

    @Test
    @DisplayName("BOX remplit exactement la zone, bornes incluses")
    void boxFillsTheWholeZoneInclusive() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 2, 1, 1), "minecraft:stone"))));

        assertTrue(result.ok(), result.error());
        // 3 × 2 × 2 = 12 cellules, bornes incluses des deux côtés.
        assertEquals(12, result.solidCount());
        Blueprint blueprint = result.blueprint();
        assertTrue(blueprint.at(0, 0, 0).isPresent());
        assertTrue(blueprint.at(2, 1, 1).isPresent());
        assertTrue(blueprint.at(3, 0, 0).isEmpty(), "rien au-delà de la borne");
        assertEquals("minecraft:stone", blueprint.at(1, 1, 1).orElseThrow().state());
    }

    @Test
    @DisplayName("SHELL ne pose que l'enveloppe : l'intérieur reste vide")
    void shellLeavesTheInsideEmpty() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.SHELL, PlanBox.of(0, 0, 0, 4, 4, 4),
                        "minecraft:stone_bricks"))));

        assertTrue(result.ok(), result.error());
        // Cube 5³ = 125 cellules, moins le noyau 3³ = 27 → 98 de coque.
        assertEquals(98, result.solidCount());
        assertTrue(result.blueprint().at(0, 2, 2).isPresent(), "la face existe");
        assertTrue(result.blueprint().at(2, 2, 2).isEmpty(), "le centre est vide");
    }

    @Test
    @DisplayName("WALLS ne pose que les quatre murs : ni sol, ni plafond")
    void wallsCarryNoFloorAndNoCeiling() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.WALLS, PlanBox.of(0, 0, 0, 4, 3, 4),
                        "minecraft:oak_planks"))));

        assertTrue(result.ok(), result.error());
        assertTrue(result.blueprint().at(0, 0, 0).isPresent(), "un coin de mur");
        assertTrue(result.blueprint().at(2, 0, 2).isEmpty(),
                "le milieu du sol doit rester vide : WALLS ne pose pas de plancher");
        assertTrue(result.blueprint().at(2, 3, 2).isEmpty(), "ni de plafond");
    }

    @Test
    @DisplayName("CLEAR perce ce qui a été posé avant — c'est ainsi qu'on fait une porte")
    void clearCarvesWhatWasPlacedBefore() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 4, 3, 4), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.CLEAR, PlanBox.of(2, 0, 0, 2, 1, 0), ""))));

        assertTrue(result.ok(), result.error());
        assertTrue(result.blueprint().at(2, 0, 0).isEmpty(), "la porte est percée");
        assertTrue(result.blueprint().at(2, 1, 0).isEmpty(), "sur les deux hauteurs");
        assertTrue(result.blueprint().at(2, 2, 0).isPresent(), "et pas au-dessus");
    }

    @Test
    @DisplayName("Une instruction postérieure recouvre une antérieure sur la même cellule")
    void alaterStepOverwritesAnEarlierOne() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 1, 0, 0), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(1, 0, 0), "minecraft:oak_planks"))));

        assertTrue(result.ok(), result.error());
        assertEquals("minecraft:stone", result.blueprint().at(0, 0, 0).orElseThrow().state());
        assertEquals("minecraft:oak_planks", result.blueprint().at(1, 0, 0).orElseThrow().state(),
                "la dernière instruction gagne");
        assertEquals(2, result.solidCount(), "et la cellule n'est comptée qu'une fois");
    }

    @Test
    @DisplayName("GABLE monte en pente et oriente les marches vers l'extérieur")
    void gableSlopesAndOrientsTheStairsOutwards() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                new PlanStep(PlanStep.Kind.GABLE, PlanBox.of(0, 0, 0, 6, 2, 4),
                        "minecraft:oak_stairs", "x"))));

        assertTrue(result.ok(), result.error());
        // Pente sur z (axe x = faîte parallèle à x) : 5 rangs, le faîte au milieu.
        // Bord bas (z=0) au niveau 0, puis 1, puis le faîte, puis on redescend.
        assertEquals(0, yOf(result.blueprint(), 0, 0));
        assertEquals(1, yOf(result.blueprint(), 0, 1));
        assertEquals(2, yOf(result.blueprint(), 0, 2), "le faîte est au centre");
        assertEquals(1, yOf(result.blueprint(), 0, 3), "et la pente redescend");
        assertEquals(0, yOf(result.blueprint(), 0, 4));

        // Les deux versants ne regardent pas du même côté : c'est tout l'intérêt de l'automatisme.
        String low = stateAt(result.blueprint(), 0, 0);
        String high = stateAt(result.blueprint(), 0, 4);
        assertTrue(low.contains("facing=south"), low);
        assertTrue(high.contains("facing=north"), high);
    }

    // ---- Déterminisme ------------------------------------------------------------------------

    /**
     * Le test qui protège le versionnage des bâtiments posés (#234) : l'empreinte du {@code .schem}
     * sert de version. Une expansion non déterministe ferait croire à un bâtiment périmé à chaque
     * régénération.
     */
    @Test
    @DisplayName("Le même plan produit exactement la même liste de blocs, dans le même ordre")
    void thesamePlanAlwaysProducesTheSameBlockListInTheSameOrder() {
        List<PlanStep> steps = List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 6, 0, 4), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.WALLS, PlanBox.of(0, 1, 0, 6, 4, 4),
                        "minecraft:stone_bricks"),
                PlanStep.of(PlanStep.Kind.CLEAR, PlanBox.of(3, 1, 0, 3, 2, 0), ""));

        var first = BuildingPlanExpander.expand(plan(steps));
        var second = BuildingPlanExpander.expand(plan(steps));

        assertTrue(first.ok() && second.ok());
        assertEquals(first.blueprint().blocks(), second.blueprint().blocks(),
                "l'ordre ET le contenu doivent être identiques");
    }

    @Test
    @DisplayName("L'ordre de la liste ne dépend pas de l'ordre des instructions")
    void theblockOrderDoesNotDependOnTheStepOrder() {
        var a = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(0, 0, 0), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(2, 0, 0), "minecraft:stone"))));
        var b = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(2, 0, 0), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(0, 0, 0), "minecraft:stone"))));

        assertEquals(a.blueprint().blocks(), b.blueprint().blocks(),
                "seule la position décide du rang : c'est ce qui rend l'empreinte stable");
    }

    // ---- Refus -------------------------------------------------------------------------------

    @Test
    @DisplayName("Un plan sans instruction, ou qui ne pose rien, est refusé")
    void aplanThatPlacesNothingIsRefused() {
        var empty = BuildingPlanExpander.expand(plan(List.of()));
        assertFalse(empty.ok());
        assertTrue(empty.error().contains("aucune instruction"), empty.error());

        var cleared = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(0, 0, 0), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.CLEAR, PlanBox.at(0, 0, 0), ""))));
        assertFalse(cleared.ok(), "un plan qui s'annule ne doit pas produire un fichier vide");
        assertTrue(cleared.error().contains("aucun bloc"), cleared.error());
    }

    @Test
    @DisplayName("Une zone qui sort du volume est refusée, en nommant l'instruction fautive")
    void azoneOutsideTheVolumeIsRefusedAndTheStepIsNamed() {
        var result = BuildingPlanExpander.expand(plan(List.of(
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(0, 0, 0), "minecraft:stone"),
                PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 99, 0, 0), "minecraft:stone"))));

        assertFalse(result.ok());
        assertTrue(result.error().contains("Instruction 2"),
                "le rang de l'instruction fautive doit être dit : " + result.error());
        assertTrue(result.error().contains("sort du volume"), result.error());
    }

    @Test
    @DisplayName("Un plan null ou invalide ne produit jamais de blueprint")
    void anullOrInvalidPlanNeverProducesABlueprint() {
        assertFalse(BuildingPlanExpander.expand(null).ok());

        BuildingPlan badId = new BuildingPlan("A B", "x", 2, 2, 2, Facing.NORTH, 0, 0, 0,
                List.of(PlanStep.of(PlanStep.Kind.BOX, PlanBox.at(0, 0, 0), "minecraft:stone")));
        var result = BuildingPlanExpander.expand(badId);
        assertFalse(result.ok());
        assertTrue(result.error().contains("Identifiant"), result.error());
    }

    @Test
    @DisplayName("Un plan dont les instructions cumulent trop de cellules est refusé avant d'allouer")
    void anoverlyExpensivePlanIsRefusedBeforeAllocating() {
        // 64 × 64 × 36 = 147 456 cellules par instruction, sous la borne de volume ; dix
        // instructions de ce gabarit dépassent la borne de COÛT cumulé.
        List<PlanStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            steps.add(PlanStep.of(PlanStep.Kind.BOX, PlanBox.of(0, 0, 0, 63, 35, 63),
                    "minecraft:stone"));
        }
        BuildingPlan big = new BuildingPlan("big_one", "Trop gros", 64, 36, 64, Facing.NORTH,
                0, 0, 0, steps);

        var result = BuildingPlanExpander.expand(big);
        assertFalse(result.ok());
        assertTrue(result.error().contains("trop coûteux"), result.error());
    }

    // ---- Helpers -----------------------------------------------------------------------------

    /** Le niveau du bloc posé en (x, z), quel qu'il soit — pour lire la forme d'une pente. */
    private static int yOf(Blueprint blueprint, int x, int z) {
        return blueprint.blocks().stream()
                .filter(b -> b.x() == x && b.z() == z)
                .mapToInt(b -> b.y()).max()
                .orElseThrow(() -> new AssertionError("aucun bloc en " + x + "/" + z));
    }

    private static String stateAt(Blueprint blueprint, int x, int z) {
        return blueprint.blocks().stream()
                .filter(b -> b.x() == x && b.z() == z)
                .findFirst().orElseThrow().state();
    }
}
