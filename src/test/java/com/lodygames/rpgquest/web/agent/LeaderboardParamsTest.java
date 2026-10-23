package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #735 — paramètres de {@code leaderboard.save} : forme stricte avant le vrai chargeur. */
class LeaderboardParamsTest {

    private static Map<String, String> valid() {
        Map<String, String> params = new HashMap<>(Map.of("board_id", "zz_halloween", "name", "Halloween",
                "category", "combat", "icon", "skull", "metric", "ENTITY_KILL", "period", "custom",
                "size", "10", "filter", "entity-family=zombies;entity=HUSK,DROWNED",
                "start", "2026-10-31T00:00+01:00"));
        params.put("end", "2026-11-02T00:00:00+01:00");
        params.put("visible", "false");
        return params;
    }

    private static String parse(Map<String, String> params, Map<String, Object> out) {
        return AgentActionExecutor.leaderboardFields(new AgentAction("a1", "leaderboard.save", params), out);
    }

    @Test
    void aValidFormBecomesTypedFields() {
        Map<String, Object> out = new LinkedHashMap<>();

        assertNull(parse(valid(), out));
        assertEquals(Map.of("entity-family", List.of("zombies"), "entity", List.of("HUSK", "DROWNED")), out.get("filter"));
        assertEquals("2026-10-30T23:00:00Z", out.get("start"), "date ramenée en UTC");
        assertEquals(10, out.get("size"));
        assertEquals(false, out.get("visible"));
        assertEquals(false, out.get("featured"));
    }

    @Test
    void malformedParametersAreRefused() {
        for (var bad : List.of(Map.of("board_id", "Bad-Id"), Map.of("metric", "NOPE"), Map.of("period", "yearly"),
                Map.of("size", "51"), Map.of("category", "vip"), Map.of("filter", "entity=ZOMBIE;'"),
                Map.of("start", "demain"), Map.of("name", "a\nb"))) {
            Map<String, String> params = valid();
            params.putAll(bad);
            assertNotNull(parse(params, new LinkedHashMap<>()), bad.toString());
        }
    }
}
