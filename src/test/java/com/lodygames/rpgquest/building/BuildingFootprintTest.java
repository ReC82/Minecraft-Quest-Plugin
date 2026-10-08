package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #213, lot « placement » — l'emprise, calculée avant tout collage.
 *
 * <p>C'est l'emprise qu'on annonce à l'administrateur, celle qui sert à détecter un chevauchement et
 * celle qu'on sauvegarde avant d'écraser. Si elle est fausse, les trois sont fausses — et la
 * sauvegarde ne couvrirait pas ce qui a réellement été écrasé.</p>
 */
class BuildingFootprintTest {

    /** La hutte du lot : 7 de large, 5 de profondeur, 6 de haut, porte en (3, 1, 0). */
    private static BuildingDefinition hut() {
        return new BuildingDefinition("test_hut_01", "Hutte de test", "", "test_hut_01.schem",
                7, 6, 5, 3, 1, 0, Facing.NORTH, List.of(), 1);
    }

    @Test
    void withoutRotationTheFootprintSurroundsTheAnchor() {
        BuildingFootprint footprint = hut().footprintAt("world_hub", 100, 70, 200, 0);

        // x : ancre 100, porte en x=3 d'un bâtiment large de 7 → de 100-3 à 100+3.
        assertEquals(97, footprint.minX());
        assertEquals(103, footprint.maxX());
        // y : ancre 70, porte en y=1 d'un bâtiment haut de 6 → la fondation descend à 69.
        assertEquals(69, footprint.minY());
        assertEquals(74, footprint.maxY());
        // z : ancre 200, porte en z=0 d'un bâtiment profond de 5 → de 200 à 204.
        assertEquals(200, footprint.minZ());
        assertEquals(204, footprint.maxZ());
    }

    /**
     * La fondation descend d'un bloc <strong>sous</strong> l'ancre, et c'est voulu.
     *
     * <p>L'ancre d'un emplacement est la case libre au-dessus du bloc cliqué ; la fondation de la
     * hutte doit donc remplacer ce bloc de sol. Si ce test échoue, la hutte flotte ou s'enfonce.</p>
     */
    @Test
    void theFoundationSitsOneBlockBelowTheAnchor() {
        BuildingFootprint footprint = hut().footprintAt("world_hub", 0, 67, 0, 0);

        assertEquals(66, footprint.minY(), "la fondation remplace le bloc de sol cliqué");
        assertEquals(67, footprint.minY() + 1, "le joueur se tient au niveau de l'ancre");
    }

    /** À 90° et 270°, l'emprise échange largeur et profondeur. C'est le test du ticket. */
    @Test
    void ninetyAndTwoHundredSeventyDegreesSwapWidthAndDepth() {
        BuildingFootprint straight = hut().footprintAt("world_hub", 0, 70, 0, 0);
        assertEquals(7, straight.sizeX());
        assertEquals(5, straight.sizeZ());

        BuildingFootprint quarter = hut().footprintAt("world_hub", 0, 70, 0, 90);
        assertEquals(5, quarter.sizeX(), "la profondeur devient la largeur");
        assertEquals(7, quarter.sizeZ(), "la largeur devient la profondeur");

        BuildingFootprint threeQuarters = hut().footprintAt("world_hub", 0, 70, 0, 270);
        assertEquals(5, threeQuarters.sizeX());
        assertEquals(7, threeQuarters.sizeZ());

        BuildingFootprint half = hut().footprintAt("world_hub", 0, 70, 0, 180);
        assertEquals(7, half.sizeX(), "un demi-tour n'échange rien");
        assertEquals(5, half.sizeZ());
    }

    /** La hauteur ne change jamais : on tourne autour de l'axe Y. */
    @Test
    void heightIsNeverAffectedByRotation() {
        for (int degrees : new int[] {0, 90, 180, 270}) {
            BuildingFootprint footprint = hut().footprintAt("world_hub", 0, 70, 0, degrees);
            assertEquals(6, footprint.sizeY(), "à " + degrees + "°");
            assertEquals(69, footprint.minY(), "à " + degrees + "°");
        }
    }

    /** L'ancre reste dans l'emprise quelle que soit la rotation : c'est un point fixe. */
    @Test
    void theAnchorStaysInsideTheFootprintAtEveryRotation() {
        for (int degrees : new int[] {0, 90, 180, 270}) {
            BuildingFootprint footprint = hut().footprintAt("world_hub", 100, 70, 200, degrees);
            assertTrue(footprint.contains("world_hub", 100, 70, 200),
                    "à " + degrees + "° l'ancre doit rester dans l'emprise : " + footprint.label());
        }
    }

    /** Le volume ne change pas : une rotation déplace des blocs, elle n'en crée pas. */
    @Test
    void theBlockCountIsStableAcrossRotations() {
        for (int degrees : new int[] {0, 90, 180, 270}) {
            assertEquals(7L * 5L * 6L,
                    hut().footprintAt("world_hub", 0, 70, 0, degrees).blockCount(),
                    "à " + degrees + "°");
        }
    }

    /**
     * Le détail qui compte à 90° : la porte reste du bon côté.
     *
     * <p>Façade de référence au nord, tournée de 90° → la façade regarde l'est, donc le corps du
     * bâtiment s'étend vers l'ouest de l'ancre. Si le signe était inversé, le bâtiment partirait à
     * l'est et la porte se retrouverait contre son propre mur.</p>
     */
    @Test
    void atNinetyDegreesTheBodyExtendsAwayFromTheDoor() {
        BuildingFootprint footprint = hut().footprintAt("world_hub", 100, 70, 200, 90);

        assertEquals(96, footprint.minX(), "le corps s'étend vers l'ouest : 100 - 4");
        assertEquals(100, footprint.maxX(), "la façade reste sur l'ancre");
        assertEquals(197, footprint.minZ());
        assertEquals(203, footprint.maxZ());
    }

    @Test
    void cornersGivenBackwardsAreNormalized() {
        BuildingFootprint footprint = new BuildingFootprint("world_hub", 10, 20, 30, 0, 5, 15);

        assertEquals(0, footprint.minX());
        assertEquals(10, footprint.maxX());
        assertEquals(5, footprint.minY());
        assertEquals(20, footprint.maxY());
    }

    @Test
    void overlapIsDetectedInTheSameWorld() {
        BuildingFootprint first = new BuildingFootprint("world_hub", 0, 0, 0, 10, 10, 10);

        assertTrue(first.overlaps(new BuildingFootprint("world_hub", 10, 10, 10, 20, 20, 20)),
                "un seul bloc commun suffit");
        assertFalse(first.overlaps(new BuildingFootprint("world_hub", 11, 0, 0, 20, 10, 10)),
                "collées sans se toucher");
    }

    /**
     * Deux mondes différents ne se chevauchent jamais, même à coordonnées identiques.
     *
     * <p>C'est l'erreur qu'un test de chevauchement naïf commet, et elle refuserait un placement
     * parfaitement légitime dans un autre monde.</p>
     */
    @Test
    void twoWorldsNeverOverlap() {
        BuildingFootprint hub = new BuildingFootprint("world_hub", 0, 0, 0, 10, 10, 10);

        assertFalse(hub.overlaps(new BuildingFootprint("claims", 0, 0, 0, 10, 10, 10)));
        assertFalse(hub.contains("claims", 5, 5, 5));
        assertTrue(hub.contains("world_hub", 5, 5, 5));
    }

    @Test
    void overlapWithNothingIsFalseRatherThanAFailure() {
        assertFalse(new BuildingFootprint("world_hub", 0, 0, 0, 1, 1, 1).overlaps(null));
    }

    @Test
    void theLabelReadsAsThreeRanges() {
        assertEquals("97..103 / 69..74 / 200..204",
                hut().footprintAt("world_hub", 100, 70, 200, 0).label());
    }
}
