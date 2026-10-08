package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingRotation;
import com.lodygames.rpgquest.building.model.Facing;
import org.junit.jupiter.api.Test;

/**
 * Issue #213, lot « placement » — la rotation, vérifiée à la main.
 *
 * <p>C'est la règle la plus facile à casser du chantier, et la plus coûteuse à constater : une
 * erreur de signe ne se voit pas dans un test d'intégration, elle se voit en jeu, une fois la hutte
 * posée à l'envers sur du terrain écrasé. Ces tests rejouent donc la convention d'axes
 * <strong>explicitement</strong>, direction par direction.</p>
 */
class BuildingRotationTest {

    @Test
    void azimuthsFollowTheCompassAndNotTheMinecraftYaw() {
        // Piège : le yaw de Minecraft met le SUD à 0. Un azimut de boussole met le NORD à 0, et
        // c'est lui qui se soustrait proprement — d'où cette table, qui n'est pas celle du yaw.
        assertEquals(0, BuildingRotation.azimuthOf(Facing.NORTH));
        assertEquals(90, BuildingRotation.azimuthOf(Facing.EAST));
        assertEquals(180, BuildingRotation.azimuthOf(Facing.SOUTH));
        assertEquals(270, BuildingRotation.azimuthOf(Facing.WEST));
    }

    /** Le cas du ticket : une façade de référence SOUTH, amenée sur les quatre orientations. */
    @Test
    void aSouthFacingReferenceReachesAllFourOrientations() {
        assertEquals(0, BuildingRotation.degreesBetween(Facing.SOUTH, Facing.SOUTH));
        assertEquals(90, BuildingRotation.degreesBetween(Facing.SOUTH, Facing.WEST));
        assertEquals(180, BuildingRotation.degreesBetween(Facing.SOUTH, Facing.NORTH));
        assertEquals(270, BuildingRotation.degreesBetween(Facing.SOUTH, Facing.EAST));
    }

    /** Et le cas réel de notre hutte, dont la façade de référence regarde le nord. */
    @Test
    void aNorthFacingReferenceReachesAllFourOrientations() {
        assertEquals(0, BuildingRotation.degreesBetween(Facing.NORTH, Facing.NORTH));
        assertEquals(90, BuildingRotation.degreesBetween(Facing.NORTH, Facing.EAST));
        assertEquals(180, BuildingRotation.degreesBetween(Facing.NORTH, Facing.SOUTH));
        assertEquals(270, BuildingRotation.degreesBetween(Facing.NORTH, Facing.WEST));
    }

    @Test
    void everyPairOfOrientationsIsReachable() {
        for (Facing reference : Facing.values()) {
            for (Facing target : Facing.values()) {
                int degrees = BuildingRotation.degreesBetween(reference, target);
                assertTrue(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270,
                        reference + " → " + target + " = " + degrees);
                // La vérification qui compte : appliquer la rotation trouvée à la référence doit
                // bien donner la cible. Sans elle, une table d'azimuts cohérente mais décalée
                // passerait tous les autres tests.
                assertEquals(target, BuildingRotation.rotate(reference, degrees),
                        reference + " tourné de " + degrees + "° doit regarder " + target);
            }
        }
    }

    /**
     * La vérification de bon sens du sens de rotation : à 90° horaires, « nord » devient « est ».
     *
     * <p>Si ce test échoue, la hutte sera posée tournée, et aucun autre test ne le dira.</p>
     */
    @Test
    void ninetyDegreesTurnsNorthIntoEast() {
        assertArrayEquals(new int[] {1, 0}, BuildingRotation.rotateOffset(0, -1, 90));
        assertArrayEquals(new int[] {0, 1}, BuildingRotation.rotateOffset(1, 0, 90));
        assertArrayEquals(new int[] {-1, 0}, BuildingRotation.rotateOffset(0, 1, 90));
        assertArrayEquals(new int[] {0, -1}, BuildingRotation.rotateOffset(-1, 0, 90));
    }

    @Test
    void oneHundredEightyDegreesInvertsBothAxes() {
        assertArrayEquals(new int[] {-3, -5}, BuildingRotation.rotateOffset(3, 5, 180));
    }

    @Test
    void twoHundredSeventyDegreesIsTheOppositeOfNinety() {
        assertArrayEquals(new int[] {-1, 0}, BuildingRotation.rotateOffset(0, -1, 270));
        assertArrayEquals(new int[] {5, -3}, BuildingRotation.rotateOffset(3, 5, 270));
    }

    @Test
    void zeroDegreesChangesNothing() {
        assertArrayEquals(new int[] {3, 5}, BuildingRotation.rotateOffset(3, 5, 0));
        assertArrayEquals(new int[] {3, 5}, BuildingRotation.rotateOffset(3, 5, 360));
    }

    /** Quatre quarts de tour reviennent au point de départ : la rotation est bien une rotation. */
    @Test
    void fourQuarterTurnsComeBackToTheStart() {
        int[] offset = {7, -2};
        for (int turn = 0; turn < 4; turn++) {
            offset = BuildingRotation.rotateOffset(offset[0], offset[1], 90);
        }
        assertArrayEquals(new int[] {7, -2}, offset);
    }

    @Test
    void onlyNinetyAndTwoHundredSeventySwapTheAxes() {
        assertFalse(BuildingRotation.swapsAxes(0));
        assertTrue(BuildingRotation.swapsAxes(90));
        assertFalse(BuildingRotation.swapsAxes(180));
        assertTrue(BuildingRotation.swapsAxes(270));
    }

    /**
     * Une rotation stockée aberrante se referme sur une valeur licite.
     *
     * <p>Mieux vaut un angle valide qu'un angle impossible propagé jusqu'au collage : la base
     * pourrait contenir une valeur écrite par une version différente.</p>
     */
    @Test
    void anyAngleNormalizesToAMultipleOfNinety() {
        assertEquals(0, BuildingRotation.normalize(0));
        assertEquals(90, BuildingRotation.normalize(90));
        assertEquals(0, BuildingRotation.normalize(360));
        assertEquals(270, BuildingRotation.normalize(-90));
        assertEquals(90, BuildingRotation.normalize(450));
        assertEquals(0, BuildingRotation.normalize(45), "45° n'est pas posable sur la grille");
        assertEquals(90, BuildingRotation.normalize(135));
    }
}
