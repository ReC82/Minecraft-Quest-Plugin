package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Un détail {@code null} est une réponse légitime (« pas de motif ») : il doit traverser le résultat
 * jusqu'au JSON au lieu de lever un {@code NullPointerException} (issue #27).
 */
class AgentActionOutcomeTest {

    @Test
    void nullDetailValuesAreKeptInOrderAndSerialized() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bridgeAvailable", true);
        details.put("reason", null);
        details.put("effective", List.of());

        AgentActionOutcome outcome = AgentActionOutcome.success("a1", "AVAILABLE", "ok", details);

        assertEquals(List.of("bridgeAvailable", "reason", "effective"), List.copyOf(outcome.details().keySet()));
        assertNull(outcome.details().get("reason"));
        assertTrue(Json.write(outcome.toJson()).contains("\"details\":{\"bridgeAvailable\":true,\"reason\":null,"
                + "\"effective\":[]}"), Json.write(outcome.toJson()));
    }

    @Test
    void detailsAreACopyThatCannotBeModified() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("k", "v");
        AgentActionOutcome outcome = AgentActionOutcome.success("a2", null, "ok", details);
        details.put("k", "changé");

        assertEquals("v", outcome.details().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> outcome.details().put("x", 1));
    }
}
