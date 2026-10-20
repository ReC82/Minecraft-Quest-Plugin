package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Issue #655 — le prompt donne au modèle le repère PROPRE au bâtiment : façades relatives traduites
 * et dernière coordonnée y permise, au lieu de le laisser les calculer (et se tromper).
 */
class BuildingPromptFrame655Test {

    @Test
    void anEastFacingBuildingGetsItsFacadeTableAndItsHeightBound() {
        String frame = BuildingPromptBuilder.frame("EAST", 10);
        // Même convention que Facade côté plugin : LEFT/RIGHT vus de l'extérieur, face à l'avant.
        assertTrue(frame.contains("FRONT = façade EST (l'entrée) · BACK = OUEST · LEFT = SUD · RIGHT = NORD"), frame);
        assertTrue(frame.contains("Jamais « facade: EAST »"), frame);
        assertTrue(frame.contains("y ≤ 9 au plus"), frame);
        assertTrue(frame.contains("un toit en y = 10 sort du volume"), frame);
        assertTrue(frame.contains("minecraft:bricks"), frame);
    }

    @Test
    void theUserPromptCarriesTheFrame() {
        String prompt = BuildingPromptBuilder.userPrompt(new BuildingPromptBuilder.Demand("p", "Boulangerie de Central",
                "EAST", "Une boulangerie.", 12, 12, 10, "boulangerie_hub"));
        assertTrue(prompt.contains("REPÈRE DE CE BÂTIMENT (front: EAST)"), prompt);
        assertTrue(BuildingPromptBuilder.frame("NORTH", 8).contains("LEFT = EST · RIGHT = OUEST"));
    }
}
