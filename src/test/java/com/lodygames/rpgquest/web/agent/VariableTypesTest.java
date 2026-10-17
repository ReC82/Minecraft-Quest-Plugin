package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #319 — seules les valeurs que le jeu comprend passent pour les variables typées. */
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
}
