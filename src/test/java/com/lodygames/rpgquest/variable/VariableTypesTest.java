package com.lodygames.rpgquest.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issues #319, #320 — seules les valeurs que le jeu comprend passent pour les variables typées. */
class VariableTypesTest {

    @Test
    void claimTiersAreTrueOrFalse() {
        assertTrue(VariableTypes.problem("CLAIM_TIER_1", "true").isEmpty());
        assertTrue(VariableTypes.problem("CLAIM_TIER_3", "false").isEmpty());
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", "lasagne").isEmpty());
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", "TRUE").isEmpty(), "le jeu ne lit que « true »");
    }

    @Test
    void theKitTierIsAWholeNumber() {
        assertTrue(VariableTypes.problem("STARTER_KIT_TIER", "2").isEmpty());
        assertFalse(VariableTypes.problem("STARTER_KIT_TIER", "0").isEmpty());
        assertFalse(VariableTypes.problem("STARTER_KIT_TIER", "true").isEmpty());
        assertFalse(VariableTypes.problem("STARTER_KIT_TIER", "1.5").isEmpty());
    }

    @Test
    void vaultUnlocksAreBooleansAndVaultSlotsAreWholeRows() {
        assertTrue(VariableTypes.problem("VAULT_UNLOCK_VAULT_02", "true").isEmpty());
        assertFalse(VariableTypes.problem("VAULT_UNLOCK_VAULT_02", "oui").isEmpty());
        assertTrue(VariableTypes.problem("VAULT_SLOTS_VAULT_01", "54").isEmpty());
        assertFalse(VariableTypes.problem("VAULT_SLOTS_VAULT_01", "40").isEmpty());
        assertFalse(VariableTypes.problem("VAULT_SLOTS_VAULT_01", "63").isEmpty());
    }

    @Test
    void otherVariablesKeepTheirFreeType() {
        assertTrue(VariableTypes.problem("tutorial_started", "lasagne").isEmpty());
        assertTrue(VariableTypes.expected("tutorial_started").isEmpty());
    }

    @Test
    void typedValuesAreComparedExactlyBecauseTheyAreStoredAsWritten() {
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", " true").isEmpty(), "« true » précédé d'un espace serait lu faux");
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", "").isEmpty());
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", null).isEmpty());
        assertFalse(VariableTypes.problem("CLAIM_TIER_1", "1").isEmpty(), "aucune conversion d'un non-booléen");
    }

    @Test
    void otherKnownBooleansAreDeclared() {
        for (String key : new String[] {"STARTER_TOOL_KIT_AVAILABLE", "WEB_PROFILE_HIDDEN", "BETA_WARNING_HIDDEN",
                "WILD_ENTRY_WARNING_HIDDEN", "RUNE_RAPPEL_GRANTED"}) {
            assertEquals("true ou false", VariableTypes.expected(key), key);
            assertEquals(VariableTypes.Kind.BOOLEAN, VariableTypes.find(key).orElseThrow().kind(), key);
        }
    }

    @Test
    void aStoredValueOutsideItsTypeIsDiagnosedAsLockedWithoutCorrection() {
        String diagnostic = VariableTypes.storedValueDiagnostic("CLAIM_TIER_1", "lasagne").orElseThrow();
        assertTrue(diagnostic.contains("lasagne"));
        assertTrue(diagnostic.contains("non débloquée"));
        assertTrue(VariableTypes.storedValueDiagnostic("CLAIM_TIER_1", "true").isEmpty());
        assertTrue(VariableTypes.storedValueDiagnostic("CLAIM_TIER_1", "false").isEmpty());
        assertTrue(VariableTypes.storedValueDiagnostic("CLAIM_TIER_1", null).isEmpty(), "absente ≠ invalide");
        assertTrue(VariableTypes.storedValueDiagnostic("tutorial_started", "lasagne").isEmpty(), "variable libre");
        assertTrue(VariableTypes.storedValueDiagnostic("STARTER_KIT_TIER", "abc").orElseThrow()
                .contains("valeur par défaut"));
    }
}
