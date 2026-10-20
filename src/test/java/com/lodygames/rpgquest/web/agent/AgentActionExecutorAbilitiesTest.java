package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #543 — lecture du paramètre « abilities » (liste JSON des cartes de capacités). */
class AgentActionExecutorAbilitiesTest {

    @Test
    void readsAListOfObjectsInOrder() {
        List<Map<String, Object>> entries = AgentActionExecutor.parseAbilityEntries(
                "[{\"type\":\"ENRAGED\",\"health-fraction\":\"0.3\"},{\"type\":\"SPLIT_ON_HIT\"}]");
        assertEquals(2, entries.size());
        assertEquals("ENRAGED", entries.get(0).get("type"));
        assertEquals("0.3", entries.get(0).get("health-fraction"));
        assertEquals("SPLIT_ON_HIT", entries.get(1).get("type"));
        assertEquals(List.of(), AgentActionExecutor.parseAbilityEntries("[]"), "liste vide = aucune capacité");
    }

    @Test
    void rejectsAnythingElse() {
        assertNull(AgentActionExecutor.parseAbilityEntries("{\"type\":\"ENRAGED\"}"));
        assertNull(AgentActionExecutor.parseAbilityEntries("[\"ENRAGED\"]"));
        assertNull(AgentActionExecutor.parseAbilityEntries("[{\"type\":"));
        assertNull(AgentActionExecutor.parseAbilityEntries("[" + "{},".repeat(AgentActionExecutor.MAX_ABILITIES_PARAM_CHARS) + "{}]"));
    }
}
