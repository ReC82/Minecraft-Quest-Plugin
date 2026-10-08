package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #213, lot « placement » — la hutte de test, vérifiée dans son plan.
 *
 * <p>C'est ici que se joue la décision de <strong>générer</strong> le schematic plutôt que de
 * déposer un binaire dans le dépôt : un blob gzip ne se relit pas en revue, et personne ne pourrait
 * affirmer qu'il mesure 7 × 5 × 6 ni que sa porte est centrée. Le plan, lui, s'inspecte — et ces
 * tests sont exactement cette inspection.</p>
 */
class TestHutBlueprintTest {

    @Test
    void theHutHasTheDimensionsTheTicketAskedFor() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        assertEquals(7, blueprint.sizeX(), "7 de large");
        assertEquals(5, blueprint.sizeZ(), "5 de profondeur");
        assertEquals(6, blueprint.sizeY(), "6 de haut");
    }

    @Test
    void thePlanIsSelfConsistent() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        assertTrue(blueprint.valid(), blueprint.validate().orElse(""));
    }

    /**
     * Le plan est déterministe : deux appels donnent exactement la même suite de blocs.
     *
     * <p>Sans cela, le fichier produit changerait d'une génération à l'autre, et on ne pourrait
     * jamais dire si une différence vient du code ou du hasard.</p>
     */
    @Test
    void thePlanIsDeterministic() {
        List<BlueprintBlock> first = TestHutBlueprint.blueprint().blocks();
        List<BlueprintBlock> second = TestHutBlueprint.blueprint().blocks();

        assertEquals(first, second);
    }

    // ---- La façade, qui est ce qu'on regarde en jeu ---------------------------------------------

    /** La porte : deux battants, au centre exact de la façade, aux hauteurs où l'on passe. */
    @Test
    void theDoorIsCenteredOnTheFacadeAtGroundLevel() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        Optional<BlueprintBlock> lower = blueprint.at(3, 1, 0);
        Optional<BlueprintBlock> upper = blueprint.at(3, 2, 0);

        assertTrue(lower.isPresent(), "battant bas en (3, 1, 0)");
        assertTrue(upper.isPresent(), "battant haut en (3, 2, 0)");
        assertEquals("oak_door", lower.get().material());
        assertEquals("oak_door", upper.get().material());
        assertTrue(lower.get().state().contains("half=lower"), lower.get().state());
        assertTrue(upper.get().state().contains("half=upper"), upper.get().state());
        // 3 est bien le centre de 0..6 : trois blocs de chaque côté.
        assertEquals(3, TestHutBlueprint.SIZE_X / 2);
    }

    /** L'ancre est la porte, et elle doit l'être : c'est la convention annoncée partout. */
    @Test
    void theAnchorIsExactlyTheDoorBlock() {
        assertEquals(TestHutBlueprint.ANCHOR_X, 3);
        assertEquals(TestHutBlueprint.ANCHOR_Y, 1);
        assertEquals(TestHutBlueprint.ANCHOR_Z, 0);

        Optional<BlueprintBlock> atAnchor = TestHutBlueprint.blueprint()
                .at(TestHutBlueprint.ANCHOR_X, TestHutBlueprint.ANCHOR_Y,
                        TestHutBlueprint.ANCHOR_Z);
        assertTrue(atAnchor.isPresent());
        assertEquals("oak_door", atAnchor.get().material(),
                "l'ancre doit tomber sur la porte, pas sur un mur");
    }

    @Test
    void twoWindowsFlankTheDoorOnTheFacade() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        assertEquals("glass_pane", blueprint.at(1, 2, 0).orElseThrow().material());
        assertEquals("glass_pane", blueprint.at(5, 2, 0).orElseThrow().material());
    }

    /**
     * La façade est dissymétrique, et c'est volontaire.
     *
     * <p>Une hutte à symétrie parfaite ne dirait rien d'une rotation de 180° : on ne saurait pas la
     * distinguer d'une rotation nulle, et le test manuel ne pourrait rien conclure.</p>
     */
    @Test
    void theFacadeDiffersFromTheRearWall() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        String facadeCenter = blueprint.at(3, 1, 0).orElseThrow().material();
        Optional<BlueprintBlock> rearCenter = blueprint.at(3, 1, 4);

        assertEquals("oak_door", facadeCenter);
        assertTrue(rearCenter.isPresent());
        assertFalse("oak_door".equals(rearCenter.get().material()),
                "le mur arrière ne doit pas avoir de porte, sinon un demi-tour est invisible");
    }

    // ---- La structure --------------------------------------------------------------------------

    @Test
    void theFoundationIsSolidStoneAcrossTheWholeBase() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 5; z++) {
                Optional<BlueprintBlock> block = blueprint.at(x, 0, z);
                assertTrue(block.isPresent(), "fondation en " + x + "/0/" + z);
                assertEquals("cobblestone", block.get().material());
            }
        }
    }

    @Test
    void theFourCornersAreVerticalLogs() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        for (int y = 1; y <= 3; y++) {
            for (int[] corner : new int[][] {{0, 0}, {6, 0}, {0, 4}, {6, 4}}) {
                Optional<BlueprintBlock> block = blueprint.at(corner[0], y, corner[1]);
                assertTrue(block.isPresent(), corner[0] + "/" + y + "/" + corner[1]);
                assertEquals("oak_log", block.get().material(),
                        "poteau d'angle en " + corner[0] + "/" + y + "/" + corner[1]);
            }
        }
    }

    /** L'intérieur est vide : les cellules non listées sont de l'air, et c'est voulu. */
    @Test
    void theInteriorIsEmpty() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 3; z++) {
                    assertTrue(blueprint.at(x, y, z).isEmpty(),
                            "l'intérieur doit rester vide en " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void theRoofHasTwoSlopesAndARidge() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        for (int x = 0; x < 7; x++) {
            assertEquals("oak_stairs", blueprint.at(x, 4, 0).orElseThrow().material());
            assertEquals("oak_stairs", blueprint.at(x, 4, 4).orElseThrow().material());
            assertEquals("oak_stairs", blueprint.at(x, 5, 1).orElseThrow().material());
            assertEquals("oak_slab", blueprint.at(x, 5, 2).orElseThrow().material(),
                    "l'arête du toit");
            assertEquals("oak_stairs", blueprint.at(x, 5, 3).orElseThrow().material());
        }
        // Les deux pans regardent vers l'extérieur, sinon le toit se lirait à l'envers.
        assertTrue(blueprint.at(0, 4, 0).orElseThrow().state().contains("facing=north"));
        assertTrue(blueprint.at(0, 4, 4).orElseThrow().state().contains("facing=south"));
    }

    @Test
    void nothingIsBuiltOutsideTheHutItself() {
        Blueprint blueprint = TestHutBlueprint.blueprint();

        // Aucun arbre, aucune clôture, aucun terrain : la seule matière hors du périmètre serait
        // de la décoration, et le ticket l'interdit explicitement.
        for (BlueprintBlock block : blueprint.blocks()) {
            assertTrue(block.x() >= 0 && block.x() < 7, block.toString());
            assertTrue(block.z() >= 0 && block.z() < 5, block.toString());
            assertTrue(block.y() >= 0 && block.y() < 6, block.toString());
        }
    }

    @Test
    void thePaletteStaysWithinTheAllowedMaterials() {
        List<String> allowed = List.of("cobblestone", "oak_log", "oak_planks", "oak_stairs",
                "oak_slab", "glass_pane", "oak_door");

        for (String material : TestHutBlueprint.blueprint().materials()) {
            assertTrue(allowed.contains(material), "matériau hors palette : " + material);
        }
    }

    // ---- La définition qui accompagne le plan ---------------------------------------------------

    /**
     * La définition lit ses dimensions <strong>sur le plan</strong>.
     *
     * <p>C'est ce qui garantit qu'elle ne peut pas mentir : une définition qui annoncerait 7 × 5 × 6
     * pour un fichier de 7 × 5 × 7 ferait annoncer une emprise fausse, et la faute ne se verrait
     * qu'après le collage.</p>
     */
    @Test
    void theDefinitionMatchesThePlanItDescribes() {
        Blueprint blueprint = TestHutBlueprint.blueprint();
        BuildingDefinition definition = TestHutBlueprint.definition();

        assertTrue(definition.valid(), definition.validate().orElse(""));
        assertEquals(blueprint.sizeX(), definition.sizeX());
        assertEquals(blueprint.sizeY(), definition.sizeY());
        assertEquals(blueprint.sizeZ(), definition.sizeZ());
        assertEquals("test_hut_01", definition.id());
        assertEquals("test_hut_01.schem", definition.schematic());
        assertEquals(Facing.NORTH, definition.front());
        assertEquals(blueprint.materials(), definition.materials());
    }

    /**
     * La façade déclarée est géométriquement vraie.
     *
     * <p>La porte est sur la paroi {@code z = 0}, qui regarde les {@code -Z}, c'est-à-dire le nord.
     * Déclarer {@code SOUTH} aurait caché un demi-tour permanent dans le code de collage — l'offset
     * implicite que ce lot doit justement éviter.</p>
     */
    @Test
    void theDeclaredFrontIsGeometricallyTrue() {
        assertEquals(Facing.NORTH, TestHutBlueprint.FRONT);
        assertEquals(0, TestHutBlueprint.ANCHOR_Z,
                "la façade est la paroi z = 0, et -Z est le nord");
        assertEquals(-1, Facing.NORTH.modZ(), "le nord est bien -Z dans ce projet");
    }
}
