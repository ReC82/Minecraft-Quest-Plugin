package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.WanderChangePlanner.Decision;
import org.junit.jupiter.api.Test;

/**
 * Règle de non-écrasement de la promenade Citizens (issue #165).
 *
 * <p>Dans Citizens, un PNJ n'a qu'<strong>un seul</strong> {@code WaypointProvider} :
 * {@code Waypoints#setWaypointProvider} retire le précédent et le remplace, sans avertir. Activer
 * la promenade sur un PNJ patrouilleur détruit donc sa patrouille. Ces tests fixent la frontière
 * entre « état neutre, on peut poser » et « comportement réel, on demande confirmation ».</p>
 */
class WanderChangePlannerTest {

    @Test
    void aNeverConfiguredNpcCanReceiveWanderDirectly() {
        // Citizens initialise tout PNJ à « linear » vide : ce n'est pas un comportement, c'est l'absence.
        assertEquals(Decision.APPLY, WanderChangePlanner.planEnable("linear", 0, false));
        assertEquals(Decision.APPLY, WanderChangePlanner.planEnable(null, 0, false));
        assertEquals(Decision.APPLY, WanderChangePlanner.planEnable("  ", 0, false));
    }

    @Test
    void anExistingLinearPatrolIsNeverReplacedWithoutConfirmation() {
        assertEquals(Decision.REQUIRES_CONFIRMATION, WanderChangePlanner.planEnable("linear", 1, false));
        assertEquals(Decision.REQUIRES_CONFIRMATION, WanderChangePlanner.planEnable("linear", 12, false));
    }

    @Test
    void aGuidedOrThirdPartyProviderIsNeverReplacedWithoutConfirmation() {
        assertEquals(Decision.REQUIRES_CONFIRMATION, WanderChangePlanner.planEnable("guided", 0, false));
        assertEquals(Decision.REQUIRES_CONFIRMATION, WanderChangePlanner.planEnable("sentinel", 0, false));
    }

    @Test
    void anExplicitConfirmationTurnsTheRefusalIntoAReplacement() {
        assertEquals(Decision.REPLACE, WanderChangePlanner.planEnable("linear", 4, true));
        assertEquals(Decision.REPLACE, WanderChangePlanner.planEnable("guided", 0, true));
    }

    @Test
    void reenablingWanderOnAWanderingNpcOnlyReconfiguresIt() {
        // Rejouer la demande (double clic, retry) ne doit ni basculer l'état ni demander de
        // confirmation : la promenade est déjà là, on ne fait que régler ancre et zone.
        assertEquals(Decision.RECONFIGURE, WanderChangePlanner.planEnable("wander", 0, false));
        assertEquals(Decision.RECONFIGURE, WanderChangePlanner.planEnable("wander", 0, true));
        assertEquals(Decision.RECONFIGURE, WanderChangePlanner.planEnable("WANDER", 0, false));
    }

    @Test
    void disablingOnlyEverRemovesWander() {
        assertEquals(Decision.DISABLE, WanderChangePlanner.planDisable("wander"));
        // Désactiver ne doit pas devenir une façon détournée d'effacer la patrouille d'autrui.
        assertEquals(Decision.NOT_WANDER, WanderChangePlanner.planDisable("linear"));
        assertEquals(Decision.NOT_WANDER, WanderChangePlanner.planDisable("guided"));
        assertEquals(Decision.NOT_WANDER, WanderChangePlanner.planDisable(null));
    }

    @Test
    void disablingIsIdempotent() {
        // Après une première désactivation, Citizens repasse en « linear » : rejouer la demande
        // répond « rien à désactiver » au lieu de réactiver quoi que ce soit.
        assertEquals(Decision.NOT_WANDER, WanderChangePlanner.planDisable("linear"));
    }

    @Test
    void theConflictDescriptionNamesWhatWouldBeLost() {
        assertTrue(WanderChangePlanner.conflictDescription("linear", 3).contains("3"));
        assertTrue(WanderChangePlanner.conflictDescription("linear", 3).contains("patrouille"));
        assertEquals("aucun comportement enregistré", WanderChangePlanner.conflictDescription("linear", 0));
        assertEquals("aucun comportement enregistré", WanderChangePlanner.conflictDescription(null, 0));
        assertTrue(WanderChangePlanner.conflictDescription("guided", 5).contains("guidé"));
        assertTrue(WanderChangePlanner.conflictDescription("sentinel", 0).contains("sentinel"),
                "un fournisseur tiers doit être nommé, pas masqué");
    }

    @Test
    void providerNamesAreComparedCaseInsensitivelyAndTrimmed() {
        assertEquals(Decision.DISABLE, WanderChangePlanner.planDisable(" Wander "));
        assertEquals(Decision.APPLY, WanderChangePlanner.planEnable(" LINEAR ", 0, false));
    }

    @Test
    void aNegativeWaypointCountIsTreatedAsEmptyRatherThanAsAConflict() {
        // Robustesse : une source qui ne sait pas compter ne doit pas bloquer l'opérateur.
        assertEquals(Decision.APPLY, WanderChangePlanner.planEnable("linear", -1, false));
    }
}
