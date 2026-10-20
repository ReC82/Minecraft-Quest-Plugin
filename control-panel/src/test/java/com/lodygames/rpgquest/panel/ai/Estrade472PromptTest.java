package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingProject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Issue #472, rejouée après #305 : « L'estrade d'Elias » a été créée sur DEV avant la colonne
 * {@code max_depth} (largeur 7, hauteur 6, longueur vide). Ce que l'IA reçoit pour ce projet doit
 * toujours permettre l'estrade attendue (7 × 6 au sol, 4 de haut) et enseigner les marches de façade.
 */
class Estrade472PromptTest {

    /** La ligne DEV telle que relue : {@code max_depth} NULL → 0. */
    private static final BuildingProject DEV_PROJECT = new BuildingProject("e7802b8d", "rpgquest-dev",
            "L'estrade d'Elias", "Petite estrade de village en bois pour Elias.", "estrade_elias", "NORTH",
            7, 0, 6, "", 1, false, "owner", Instant.EPOCH, "owner", Instant.EPOCH);

    private static BuildingPromptBuilder.Demand demand() {
        BuildingProject p = DEV_PROJECT;
        return new BuildingPromptBuilder.Demand(p.id(), p.name(), p.front(), p.brief(), p.maxWidth(),
                p.maxDepth(), p.maxHeight(), p.buildingId());
    }

    @Test
    void aProjectWithoutDepthKeepsASquareLimitThatHoldsTheEstrade() {
        assertEquals(7, DEV_PROJECT.maxDepth(), "projet antérieur à #305 : Z = X");
        String user = BuildingPromptBuilder.userPrompt(demand());
        assertTrue(user.contains("size.x ≤ 7 ET size.z ≤ 7"), user);
        assertTrue(user.contains("Hauteur maximale : 6 blocs"), user);
        assertTrue(user.contains("front: NORTH"), user);
        assertTrue(user.contains("id: estrade_elias"), user);
    }

    @Test
    void theSystemPromptStillTeachesFrontStepsOnTheTerrain() {
        String system = BuildingPromptBuilder.systemPrompt();
        assertTrue(system.contains("MARCHES D'ENTRÉE posées sur le terrain"), "usage estrade décrit");
        int example = system.indexOf("id: exemple_estrade");
        assertTrue(example > 0, "l'exemple d'estrade vérifié est toujours là");
        String exampleSteps = system.substring(example, Math.min(system.length(), example + 600));
        assertTrue(exampleSteps.contains("op: staircase") && exampleSteps.contains("facade: FRONT"),
                "les marches de l'exemple sont écrites par façade : " + exampleSteps);
    }
}
