package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Matrice complète de la décision « renommer sans changer l'apparence ».
 *
 * <p>Le point à ne jamais perdre : {@code citizensapi} n'expose aucune API de skin, donc une
 * apparence que RPGQuest n'a pas posée lui-même est <strong>illisible</strong>. Le planificateur
 * doit refuser dans ce cas, et non « préserver » en rattachant l'ancien nom — ce qui écraserait un
 * skin explicite par une texture dérivée d'un pseudo.</p>
 */
class SkinPreservationPlannerTest {

    @Test
    void aNonPlayerNpcHasNoSkinSoRenamingIsAlwaysAllowed() {
        SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(Optional.of(false), false);

        assertEquals(SkinPreservationPlanner.Decision.RENAME_FREELY, plan.decision());
        assertTrue(plan.allowsRename());
        assertNull(plan.code(), "aucun code d'erreur");
        assertTrue(plan.message().contains("pas de skin"), "la raison est dite");
    }

    @Test
    void aNonPlayerNpcIsAllowedEvenWithARecordedSource() {
        // Cohérence : le type décide, pas la présence d'un enregistrement résiduel.
        assertTrue(SkinPreservationPlanner.plan(Optional.of(false), true).allowsRename());
    }

    @Test
    void aPlayerNpcWithARecordedSourceIsRenamedAfterReapplyingIt() {
        SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(Optional.of(true), true);

        assertEquals(SkinPreservationPlanner.Decision.REAPPLY_RECORDED_THEN_RENAME, plan.decision());
        assertTrue(plan.allowsRename());
        assertNull(plan.code());
        assertTrue(plan.message().contains("Skin conservé"));
    }

    @Test
    void aPlayerNpcWithAnUnknownSkinIsRefusedRatherThanSilentlyReskinned() {
        SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(Optional.of(true), false);

        assertEquals(SkinPreservationPlanner.Decision.REFUSE_SKIN_SOURCE_UNKNOWN, plan.decision());
        assertFalse(plan.allowsRename(), "ne jamais renommer à l'aveugle un PNJ joueur");
        assertEquals("SKIN_SOURCE_UNKNOWN", plan.code());
        // Le message doit expliquer la limite ET donner la sortie, sinon le refus est inutilisable.
        assertTrue(plan.message().contains("n'expose aucun moyen de le lire"), "la limite est expliquée");
        assertTrue(plan.message().contains("Appliquer d'abord le skin voulu"), "la sortie est donnée");
    }

    @Test
    void anUndeterminableTypeIsRefusedToo() {
        SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(Optional.empty(), true);

        assertEquals(SkinPreservationPlanner.Decision.REFUSE_TYPE_UNKNOWN, plan.decision());
        assertFalse(plan.allowsRename());
        assertEquals("NPC_TYPE_UNKNOWN", plan.code());
    }

    @Test
    void aNullTypeIsTreatedAsUndeterminableNotAsNonPlayer() {
        // Défensif : un appelant qui passe null ne doit pas obtenir un renommage « libre ».
        assertEquals(SkinPreservationPlanner.Decision.REFUSE_TYPE_UNKNOWN,
                SkinPreservationPlanner.plan(null, true).decision());
    }

    @Test
    void everyRefusalCarriesACodeAndEveryAllowanceCarriesNone() {
        for (boolean recorded : new boolean[] {true, false}) {
            for (Optional<Boolean> type : java.util.List.of(
                    Optional.of(true), Optional.of(false), Optional.<Boolean>empty())) {
                SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(type, recorded);
                assertEquals(plan.allowsRename(), plan.code() == null,
                        "un refus doit porter un code, une autorisation non : " + type + "/" + recorded);
                assertFalse(plan.message().isBlank(), "toujours un message lisible");
            }
        }
    }
}
