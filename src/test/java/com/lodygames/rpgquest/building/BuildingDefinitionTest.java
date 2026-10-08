package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #213, lot « placement » — la validation d'une définition de bâtiment.
 *
 * <p>Une définition est du contenu, donc elle peut être fausse : écrite à la main, recopiée,
 * modifiée. Le but de ces tests est qu'une définition fausse soit <strong>refusée avec un motif
 * lisible</strong> au chargement, et jamais découverte après un collage — moment où elle aurait déjà
 * écrasé des blocs à la mauvaise place.</p>
 */
class BuildingDefinitionTest {

    private static BuildingDefinition of(int sizeX, int sizeY, int sizeZ,
                                         int anchorX, int anchorY, int anchorZ) {
        return new BuildingDefinition("test_hut_01", "Hutte de test", "", "test_hut_01.schem",
                sizeX, sizeY, sizeZ, anchorX, anchorY, anchorZ, Facing.NORTH, List.of(), 1);
    }

    @Test
    void aWellFormedDefinitionIsAccepted() {
        BuildingDefinition definition = of(7, 6, 5, 3, 1, 0);

        assertTrue(definition.valid(), definition.validate().orElse(""));
        assertEquals("7 × 5 × 6", definition.sizeLabel());
        assertEquals("3 / 1 / 0", definition.anchorLabel());
        assertEquals(210L, definition.blockCount());
    }

    // ---- Dimensions ----------------------------------------------------------------------------

    @Test
    void zeroOrNegativeDimensionsAreRefused() {
        for (BuildingDefinition broken : List.of(of(0, 6, 5, 0, 0, 0), of(7, 0, 5, 0, 0, 0),
                of(7, 6, 0, 0, 0, 0), of(-7, 6, 5, 0, 0, 0))) {
            assertFalse(broken.valid());
            assertTrue(broken.validate().orElse("").startsWith("Dimensions invalides"),
                    broken.validate().orElse(""));
        }
    }

    /** Une borne haute existe : un schematic de 2000 blocs de côté n'est pas un bâtiment. */
    @Test
    void absurdlyLargeDimensionsAreRefused() {
        BuildingDefinition broken = of(BuildingDefinition.MAX_SIZE + 1, 6, 5, 0, 0, 0);

        assertFalse(broken.valid());
        assertTrue(broken.validate().orElse("").contains("trop grandes"),
                broken.validate().orElse(""));
    }

    // ---- Ancre ---------------------------------------------------------------------------------

    /**
     * Une ancre hors du bâtiment est refusée.
     *
     * <p>C'est la faute la plus sournoise : rien n'échoue, mais l'emprise annoncée est décalée du
     * bâtiment réel, et le décalage ne se voit qu'en jeu une fois la hutte posée à côté de son
     * emplacement.</p>
     */
    @Test
    void anAnchorOutsideTheBuildingIsRefused() {
        for (BuildingDefinition broken : List.of(
                of(7, 6, 5, 7, 1, 0),   // x == sizeX
                of(7, 6, 5, -1, 1, 0),  // x négatif
                of(7, 6, 5, 3, 6, 0),   // y == sizeY
                of(7, 6, 5, 3, 1, 5))) { // z == sizeZ
            assertFalse(broken.valid(), broken.anchorLabel());
            assertTrue(broken.validate().orElse("").startsWith("Ancre hors du bâtiment"),
                    broken.validate().orElse(""));
        }
    }

    @Test
    void anAnchorOnTheLastBlockIsAccepted() {
        assertTrue(of(7, 6, 5, 6, 5, 4).valid(), "les bornes sont inclusives");
    }

    // ---- Orientation ---------------------------------------------------------------------------

    @Test
    void aMissingReferenceOrientationIsRefused() {
        BuildingDefinition broken = new BuildingDefinition("test_hut_01", "Hutte", "",
                "test_hut_01.schem", 7, 6, 5, 3, 1, 0, null, List.of(), 1);

        assertFalse(broken.valid());
        assertTrue(broken.validate().orElse("").contains("orientation de référence"),
                broken.validate().orElse(""));
    }

    @Test
    void theRotationIsComputedFromTheReferenceOrientation() {
        BuildingDefinition north = of(7, 6, 5, 3, 1, 0);

        assertEquals(0, north.rotationFor(Facing.NORTH));
        assertEquals(90, north.rotationFor(Facing.EAST));
        assertEquals(180, north.rotationFor(Facing.SOUTH));
        assertEquals(270, north.rotationFor(Facing.WEST));
    }

    // ---- Identifiant et nom de fichier ---------------------------------------------------------

    @Test
    void anInvalidIdentifierIsRefused() {
        // Majuscules, tirets, deux-points et longueur excessive : refusés. La forme est alignée sur
        // celle du reste du contenu du projet (ContentId.KEY_PATTERN), pour qu'un identifiant de
        // bâtiment se lise et se saisisse comme un identifiant de quête ou de dialogue.
        for (String id : List.of("", "Test_Hut", "test-hut", "rpgquest:hut", "_hut",
                "a".repeat(80))) {
            BuildingDefinition broken = new BuildingDefinition(id, "Hutte", "",
                    "test_hut_01.schem", 7, 6, 5, 3, 1, 0, Facing.NORTH, List.of(), 1);
            assertFalse(broken.valid(), "« " + id + " » ne devrait pas être accepté");
        }
    }

    /**
     * Un chiffre en première position est <strong>accepté</strong>, comme partout ailleurs dans le
     * contenu du projet.
     *
     * <p>Noté explicitement parce que l'intuition dit le contraire : la convention du dépôt est
     * {@code [a-z0-9][a-z0-9_]*}, et la respecter vaut mieux qu'inventer ici une règle plus stricte
     * que celle des quêtes et des dialogues.</p>
     */
    @Test
    void anIdentifierStartingWithADigitIsAccepted() {
        BuildingDefinition definition = new BuildingDefinition("2nd_hut", "Seconde hutte", "",
                "test_hut_01.schem", 7, 6, 5, 3, 1, 0, Facing.NORTH, List.of(), 1);

        assertTrue(definition.valid(), definition.validate().orElse(""));
    }

    /**
     * Le nom de fichier ne peut pas désigner un chemin.
     *
     * <p>Il vient d'un fichier de contenu, mais il est utilisé pour ouvrir un fichier : une
     * traversée de chemin y lirait ou écrirait n'importe où sur le serveur.</p>
     */
    @Test
    void aSchematicNameCannotEscapeItsDirectory() {
        for (String schematic : List.of("", "hut.schematic", "../secret.schem",
                "sub/hut.schem", "..\\hut.schem", "hut", "HUT.schem")) {
            BuildingDefinition broken = new BuildingDefinition("test_hut_01", "Hutte", "",
                    schematic, 7, 6, 5, 3, 1, 0, Facing.NORTH, List.of(), 1);
            assertFalse(broken.valid(), "« " + schematic + " » ne devrait pas être accepté");
        }
        assertTrue(of(7, 6, 5, 3, 1, 0).valid());
    }

    @Test
    void aMissingNameIsRefused() {
        BuildingDefinition broken = new BuildingDefinition("test_hut_01", "  ", "",
                "test_hut_01.schem", 7, 6, 5, 3, 1, 0, Facing.NORTH, List.of(), 1);

        assertFalse(broken.valid());
        assertTrue(broken.validate().orElse("").contains("n'a pas de nom"));
    }

    // ---- Tolérance -----------------------------------------------------------------------------

    @Test
    void aMissingDescriptionOrMaterialListIsToleratedRatherThanRefused() {
        BuildingDefinition definition = new BuildingDefinition("test_hut_01", "Hutte", null,
                "test_hut_01.schem", 7, 6, 5, 3, 1, 0, Facing.NORTH, null, 1);

        assertTrue(definition.valid());
        assertEquals("", definition.description());
        assertEquals(List.of(), definition.materials());
        assertEquals("—", definition.materialsLabel());
    }

    @Test
    void theMaterialLabelIsReadable() {
        BuildingDefinition definition = new BuildingDefinition("test_hut_01", "Hutte", "",
                "test_hut_01.schem", 7, 6, 5, 3, 1, 0, Facing.NORTH,
                List.of("OAK_LOG", "oak_log", "COBBLESTONE"), 1);

        assertEquals("oak log, cobblestone", definition.materialsLabel(),
                "minuscules, sans souligné, et sans doublon");
    }
}
