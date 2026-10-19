package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #320 — mêmes cas que {@code VariableTypesTest} du plugin : les deux registres concordent. */
class KnownVariableTypesTest {

    @Test
    void declaredBooleansOnlyAcceptTheExactStringsTrueOrFalse() {
        assertTrue(KnownVariableTypes.problem("CLAIM_TIER_1", "true").isEmpty());
        assertTrue(KnownVariableTypes.problem("CLAIM_TIER_3", "false").isEmpty());
        for (String bad : new String[] {"lasagne", "TRUE", " true", "", "1"}) {
            assertFalse(KnownVariableTypes.problem("CLAIM_TIER_1", bad).isEmpty(), bad);
        }
        assertFalse(KnownVariableTypes.problem("STARTER_TOOL_KIT_AVAILABLE", "oui").isEmpty());
        assertFalse(KnownVariableTypes.problem("VAULT_UNLOCK_VAULT_02", "oui").isEmpty());
    }

    @Test
    void numbersAndChoicesFollowThePlugin() {
        assertTrue(KnownVariableTypes.problem("STARTER_KIT_TIER", "2").isEmpty());
        assertFalse(KnownVariableTypes.problem("STARTER_KIT_TIER", "0").isEmpty());
        assertTrue(KnownVariableTypes.problem("VAULT_SLOTS_VAULT_01", "54").isEmpty());
        assertFalse(KnownVariableTypes.problem("VAULT_SLOTS_VAULT_01", "40").isEmpty());
    }

    @Test
    void freeVariablesStayFree() {
        assertTrue(KnownVariableTypes.problem("tutorial_started", "lasagne").isEmpty());
        assertTrue(KnownVariableTypes.find("met_lily").isEmpty());
    }

    @Test
    void everyKeyOfTheBooleanSelectorIsADeclaredBoolean() {
        for (String key : KnownVariableTypes.BOOLEAN_KEYS) {
            assertTrue(KnownVariableTypes.find(key).orElseThrow().bool(), key);
        }
    }
}
