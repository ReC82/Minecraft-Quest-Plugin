package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Issue #256 — un étage exigé par le brief et absent de la grille ne passe plus en silence.
 */
class BriefLevelsTest {

    /** Le brief réel de {@code buildsite_0009}, mot pour mot. */
    private static final String HUB_LIBRARY = "Bibliothèque médiévale fantastique destinée au Hub "
            + "de LodyQuests.\r\n\r\nBâtiment sur deux étages, principalement en pierre claire et "
            + "bois sombre, avec une façade orientée vers l'ouest.\r\n\r\nPrévoir une grande salle "
            + "de lecture, des bibliothèques, un comptoir pour le futur PNJ bibliothécaire, un "
            + "escalier intérieur, des fenêtres et un toit en pente.";

    @Test
    void theHubLibraryBriefAsksForTwoLevels() {
        assertEquals(OptionalInt.of(2), BriefLevels.expected(HUB_LIBRARY));
    }

    /** Le cas réel de l'action 7a1ab38d : floors 1, reachableFloors 1, verdict « conforme ». */
    @Test
    void theMeasuredSingleLevelIsReportedAsADivergence() {
        var warning = BriefLevels.divergence(HUB_LIBRARY,
                Map.of("floors", 1, "reachableFloors", 1, "solidCells", 2107));

        assertTrue(warning.isPresent(), "un étage absent ne doit plus passer en silence");
        assertTrue(warning.get().contains("2 niveaux"), warning.get());
        assertTrue(warning.get().contains("n'en compte que 1"), warning.get());
    }

    @Test
    void enoughReachableLevelsMeanNoWarning() {
        assertTrue(BriefLevels.divergence(HUB_LIBRARY,
                Map.of("floors", 2, "reachableFloors", 2)).isEmpty());
    }

    @Test
    void anUnreachableUpperLevelIsReportedToo() {
        var warning = BriefLevels.divergence(HUB_LIBRARY, Map.of("floors", 2, "reachableFloors", 1));
        assertTrue(warning.orElseThrow().contains("seulement 1 atteignable"), warning.get());
    }

    @Test
    void theUsualFrenchFormsAreRead() {
        assertEquals(OptionalInt.of(2), BriefLevels.expected("Maison R+1 en colombages"));
        assertEquals(OptionalInt.of(3), BriefLevels.expected("Tour sur 3 niveaux"));
        assertEquals(OptionalInt.of(2), BriefLevels.expected("Petite maison à étage"));
        assertEquals(OptionalInt.of(2), BriefLevels.expected("Auberge avec un étage"));
        assertEquals(OptionalInt.of(3), BriefLevels.expected("TROIS ÉTAGES, toit d’ardoise"));
    }

    /** Une mention d'étage qui ne compte rien n'est pas une exigence. */
    @Test
    void aMentionThatCountsNothingIsIgnored() {
        assertTrue(BriefLevels.expected("Un comptoir au premier étage, près de l'escalier")
                .isEmpty());
        assertTrue(BriefLevels.expected("Maison de plain-pied").isEmpty());
        assertTrue(BriefLevels.expected("").isEmpty());
        assertTrue(BriefLevels.expected(null).isEmpty());
    }

    /** Sans mesure de niveaux (ancien plugin), on ne prétend rien. */
    @Test
    void withoutAMeasureNothingIsClaimed() {
        assertTrue(BriefLevels.divergence(HUB_LIBRARY, Map.of("solidCells", 10)).isEmpty());
        assertTrue(BriefLevels.divergence("Un abri simple.", Map.of("floors", 1)).isEmpty());
    }
}
