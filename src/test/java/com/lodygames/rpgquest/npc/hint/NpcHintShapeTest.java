package com.lodygames.rpgquest.npc.hint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #12 (retour du 12/10) — la forme du signal : « ! » pour une quête, « … » face au joueur. */
class NpcHintShapeTest {

    @Test
    void aQuestIsAVerticalExclamationMarkWithAGap() {
        List<double[]> points = NpcHintService.hintShape(NpcHintService.Hint.QUEST, 3, 0);

        assertEquals(5, points.size());
        for (double[] point : points) {
            assertEquals(0.0, point[0], 1e-9, "vertical : aucun décalage horizontal");
            assertEquals(0.0, point[2], 1e-9);
        }
        assertTrue(points.get(1)[1] - points.get(0)[1] > 0.2, "le point du bas est séparé de la barre");
        assertTrue(points.get(4)[1] <= 0.7, "la forme reste basse, juste au-dessus du nom");
    }

    @Test
    void aDialogueIsThreeDotsFacingThePlayer() {
        // Joueur à l'est du PNJ : les points s'alignent nord-sud pour être vus de face.
        List<double[]> points = NpcHintService.hintShape(NpcHintService.Hint.DIALOGUE, 5, 0);

        assertEquals(3, points.size());
        for (double[] point : points) {
            assertEquals(0.0, point[0], 1e-9);
            assertEquals(0.2, point[1], 1e-9, "même hauteur : une ligne");
        }
        assertEquals(0.4, Math.abs(points.get(2)[2] - points.get(0)[2]), 1e-9);
    }

    @Test
    void aPlayerStandingOnTheNpcStillGetsAShape() {
        assertEquals(3, NpcHintService.hintShape(NpcHintService.Hint.DIALOGUE, 0, 0).size());
    }
}
