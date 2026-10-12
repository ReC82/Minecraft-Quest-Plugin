package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #269 — l'ancre de chaque bâtiment est décrite telle qu'elle est, pas selon une convention unique. */
class AnchorMeaningTest {

    @Test
    void theTestHutAnchorIsTheMiddleOfItsEntranceOneBlockUp() {
        // Hutte de test : 7 × 6 × 5, façade nord, ancre 3 / 1 / 0 (milieu de la porte, sur la fondation).
        String text = AnchorMeaning.describe(7, 6, 5, 3, 1, 0, "NORTH");
        assertEquals("Le repère de l'emplacement tombe au milieu de la façade d'entrée, 1 bloc au-dessus du "
                + "bas du bâtiment (ce qui est plus bas s'enfonce dans le sol).", text);
    }

    @Test
    void aWorkshopBuildingWithoutDeclaredAnchorIsCentredAtTheBottom() {
        // Plan de l'Atelier sans ancre : le plugin prend sizeX / 2, 0, sizeZ / 2.
        assertEquals("Le repère de l'emplacement tombe au centre du bâtiment, au niveau du bas du bâtiment.",
                AnchorMeaning.describe(19, 14, 19, 9, 0, 9, "WEST"));
        assertTrue(AnchorMeaning.isDefault(20, 16, 10, 0, 8), "largeur paire : sizeX / 2");
    }

    @Test
    void anOddPointIsNotDressedUpAsAConvention() {
        assertTrue(AnchorMeaning.describe(10, 5, 10, 2, 0, 7, "SOUTH").contains("en un point choisi par le plan"));
        assertTrue(AnchorMeaning.describe(10, 5, 10, 1, 0, 9, "SOUTH").contains("sur la façade d'entrée"));
    }
}
