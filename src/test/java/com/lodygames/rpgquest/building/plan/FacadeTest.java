package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Facing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot C — la résolution des façades, sur les quatre orientations.
 *
 * <p>C'est le lot qui supprime l'erreur ayant scellé la V1 : le modèle devait traduire
 * {@code front: WEST} en {@code x = 0} et avait écrit {@code x = 5}. Ces tests figent la
 * correspondance pour que plus personne n'ait à la calculer.</p>
 */
class FacadeTest {

    @Test
    @DisplayName("FRONT suit l'orientation déclarée, sur les quatre points cardinaux")
    void frontFollowsTheDeclaredOrientation() {
        assertEquals(BlockStates.Dir.WEST, Facade.FRONT.direction(Facing.WEST));
        assertEquals(BlockStates.Dir.NORTH, Facade.FRONT.direction(Facing.NORTH));
        assertEquals(BlockStates.Dir.EAST, Facade.FRONT.direction(Facing.EAST));
        assertEquals(BlockStates.Dir.SOUTH, Facade.FRONT.direction(Facing.SOUTH));
    }

    @Test
    @DisplayName("BACK est toujours l'opposé, et LEFT/RIGHT sont opposées entre elles")
    void backIsOppositeAndSidesAreOpposedToEachOther() {
        for (Facing front : Facing.values()) {
            assertEquals(Facade.FRONT.direction(front).opposite(), Facade.BACK.direction(front),
                    "arrière de " + front);
            assertEquals(Facade.LEFT.direction(front).opposite(), Facade.RIGHT.direction(front),
                    "gauche et droite de " + front);
            // Les quatre façades désignent quatre directions distinctes.
            assertEquals(4, java.util.Set.of(
                    Facade.FRONT.direction(front), Facade.BACK.direction(front),
                    Facade.LEFT.direction(front), Facade.RIGHT.direction(front)).size(),
                    "quatre façades distinctes pour " + front);
        }
    }

    /**
     * Le cas exact de la V1 : {@code front: WEST}, et une porte sur la façade avant doit tomber en
     * {@code x = 0}.
     */
    @Test
    @DisplayName("Avec front: WEST, la façade avant est bien le plan x=0")
    void withFrontWestTheFrontFacadeIsThePlaneXZero() {
        int[] cell = Facade.FRONT.cell(Facing.WEST, 19, 14, 19, 9, 1).orElseThrow();

        assertEquals(0, cell[0], "x doit valoir 0 — le modèle avait écrit 5");
        assertEquals(1, cell[1]);
        assertEquals(9, cell[2], "le décalage court selon Z sur une façade est/ouest");
    }

    @Test
    @DisplayName("Sur une façade nord/sud, le décalage court selon X")
    void onANorthSouthFacadeTheOffsetRunsAlongX() {
        int[] cell = Facade.FRONT.cell(Facing.NORTH, 11, 8, 9, 3, 2).orElseThrow();

        assertEquals(3, cell[0]);
        assertEquals(2, cell[1]);
        assertEquals(0, cell[2], "une façade nord est le plan z=0");
    }

    @Test
    @DisplayName("La façade arrière est le plan de coordonnée maximale")
    void thebackFacadeIsThePlaneOfMaximalCoordinate() {
        assertEquals(18, Facade.BACK.cell(Facing.WEST, 19, 14, 19, 5, 1).orElseThrow()[0]);
        assertEquals(8, Facade.BACK.cell(Facing.NORTH, 11, 8, 9, 5, 1).orElseThrow()[2]);
    }

    @Test
    @DisplayName("Un décalage hors de la façade est refusé, pas replié")
    void anOffsetBeyondTheFacadeIsRefusedNotWrapped() {
        assertTrue(Facade.FRONT.cell(Facing.WEST, 9, 8, 7, 7, 1).isEmpty(),
                "la façade ouest d'un plan de profondeur 7 n'a que les décalages 0 à 6");
        assertTrue(Facade.FRONT.cell(Facing.WEST, 9, 8, 7, -1, 1).isEmpty());
        assertTrue(Facade.FRONT.cell(Facing.WEST, 9, 8, 7, 3, 99).isEmpty(), "hauteur hors volume");
    }

    @Test
    @DisplayName("Le pas vers l'intérieur entre bien dans le bâtiment")
    void theinwardStepGoesIntoTheBuilding() {
        // Façade ouest (x=0) : l'intérieur est vers les x croissants.
        int[] step = Facade.FRONT.inwardStep(Facing.WEST);
        assertEquals(1, step[0]);
        assertEquals(0, step[2]);
        // Façade arrière du même bâtiment (x=max) : l'intérieur est vers les x décroissants.
        assertEquals(-1, Facade.BACK.inwardStep(Facing.WEST)[0]);
        // Façade nord (z=0) : l'intérieur est vers les z croissants.
        assertEquals(1, Facade.FRONT.inwardStep(Facing.NORTH)[2]);
    }

    @Test
    @DisplayName("La largeur d'une façade est celle de l'axe qu'elle parcourt")
    void thefacadeSpanIsThatOfTheAxisItRuns() {
        // Façade est/ouest : elle court selon Z, donc sa largeur est sizeZ.
        assertEquals(19, Facade.FRONT.span(Facing.WEST, 11, 19));
        // Façade nord/sud : elle court selon X.
        assertEquals(11, Facade.FRONT.span(Facing.NORTH, 11, 19));
    }

    @Test
    @DisplayName("Un nom de façade inconnu est refusé, pas interprété")
    void anUnknownFacadeNameIsRefused() {
        assertTrue(Facade.of("devant").isEmpty(), "pas de traduction implicite");
        assertTrue(Facade.of("").isEmpty());
        assertTrue(Facade.of(null).isEmpty());
        assertEquals(Facade.FRONT, Facade.of("front").orElseThrow());
        assertEquals(Facade.RIGHT, Facade.of("RIGHT").orElseThrow());
    }
}
