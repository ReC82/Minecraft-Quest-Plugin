package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #655 — la description et le sélecteur « Façade d'entrée » qui se contredisent sont
 * signalés avant tout appel payant ; une description floue ne bloque jamais rien.
 */
class BriefOrientationTest {

    /** Les phrases réelles du projet « Boulangerie de Central » (96a24bba), sélecteur EAST. */
    private static final String BOULANGERIE = """
            Façade principale et entrée à l’OUEST, arrière à l’EST. Un seul niveau utilisable.

            ACCÈS
            Entrée sur la moitié gauche de la façade ouest lorsqu’on regarde le bâtiment depuis
            l’extérieur, donc côté nord. Installer un four décoratif contre le mur est.
            """;

    @Test
    void theRealBakeryBriefAsksForAWestEntranceAndContradictsTheEastSelector() {
        assertEquals(Optional.of("WEST"), BriefOrientation.requestedFront(BOULANGERIE));
        String conflict = BriefOrientation.conflict(BOULANGERIE, "EAST").orElseThrow();
        assertTrue(conflict.contains("l'entrée à l'OUEST"), conflict);
        assertTrue(conflict.contains("choisie dans le projet est EST"), conflict);
        assertTrue(BriefOrientation.conflict(BOULANGERIE, "WEST").isEmpty(), "sélecteur aligné : rien à dire");
    }

    @Test
    void usualPhrasingsAreRead() {
        assertEquals(Optional.of("NORTH"), BriefOrientation.requestedFront("L'entrée est au nord, côté place."));
        assertEquals(Optional.of("SOUTH"), BriefOrientation.requestedFront("Entrée principale plein sud."));
        assertEquals(Optional.of("EAST"), BriefOrientation.requestedFront("Façade d'entrée à l'est."));
        assertEquals(Optional.of("EAST"), BriefOrientation.requestedFront("une entrée côté est"));
    }

    @Test
    void anAmbiguousOrSilentBriefNeverBlocks() {
        assertTrue(BriefOrientation.requestedFront("Entrée à l'ouest, puis une seconde entrée au sud.").isEmpty());
        assertTrue(BriefOrientation.requestedFront("Une petite maison avec un four à l'est.").isEmpty());
        assertTrue(BriefOrientation.requestedFront("Le mur est est en briques.").isEmpty());
        assertTrue(BriefOrientation.conflict("", "EAST").isEmpty());
        assertTrue(BriefOrientation.conflict("Entrée au nord.", "").isEmpty());
    }
}
