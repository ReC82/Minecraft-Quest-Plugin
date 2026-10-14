package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #13 : classement « Secondaires / Rencontres » et délai affiché dans /quests. */
class EncounterQuestFilterTest {

    @Test
    void encounterQuestsAreRecognisedByCategoryOrGiver() {
        assertTrue(AgentPages.isEncounterQuest("encounter", ""));
        assertTrue(AgentPages.isEncounterQuest("combat", "encounter_bandit"));
        assertTrue(AgentPages.isEncounterQuest("", "rpgquest:encounter_smith"));
        assertFalse(AgentPages.isEncounterQuest("combat", "guard"));
        assertFalse(AgentPages.isEncounterQuest(null, null));
    }

    @Test
    void cooldownLabelPrefersTheServerValue() {
        assertEquals("20 h", AgentPages.questCooldownLabel(Map.of("cooldownSeconds", 72000L, "cooldown", "1d")));
        assertEquals("1 j 2 h 30 min", AgentPages.questCooldownLabel(Map.of("cooldownSeconds", 95400)));
        assertEquals("45 s", AgentPages.questCooldownLabel(Map.of("cooldownSeconds", 45)));
        assertEquals("1d", AgentPages.questCooldownLabel(Map.of("cooldown", "1d")));
        assertEquals("", AgentPages.questCooldownLabel(Map.of()));
    }
}
