package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.http.MalformedRequestException;
import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.site.DemoData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #53 : contrat de la progression personnelle, lu par liste blanche et rendu sans fuite. */
class LinkedProgressTest {

    private static Map<?, ?> wire(String json) {
        return (Map<?, ?>) Json.parse(json);
    }

    @Test
    void roundTripsThroughStorageAndDropsUnknownFields() {
        LinkedProgress progress = LinkedProgress.fromWire(wire("""
                {"generatedAt":"2026-11-20T10:00:00Z","intrus":"x",
                 "skills":[{"skill":"GLOBAL","xp":10,"level":1,"uuid":"secret"}],
                 "quests":{"completed":0},
                 "boards":[{"id":"b1","name":"B","category":"combat","metric":"RPG_XP_COMBAT","period":"weekly",
                            "window":"2026-W47","status":"ENDED","value":5,"rank":1,"players":1,"name2":"Bob"}]}
                """));

        String stored = Json.write(progress.toWire());
        assertFalse(stored.contains("intrus") || stored.contains("secret") || stored.contains("Bob"));
        assertEquals(progress, LinkedProgress.fromWire(Json.parse(stored)));
        assertFalse(progress.quests().ranked());
    }

    @Test
    void boundsListSizes() {
        List<Map<String, Object>> boards = new ArrayList<>();
        for (int i = 0; i <= LinkedProgress.MAX_BOARDS; i++) {
            boards.add(Map.of("id", "b" + i, "name", "B", "category", "combat", "metric", "M", "period", "daily",
                    "window", "w", "status", "ACTIVE", "value", 0L));
        }
        Map<String, Object> raw = Map.of("generatedAt", "2026-11-20T10:00:00Z", "skills", List.of(),
                "quests", Map.of("completed", 0L), "boards", boards);
        assertThrows(MalformedRequestException.class, () -> LinkedProgress.fromWire(raw));
    }

    @Test
    void demoProgressIsConsistentAndRendered() {
        LinkedProgress demo = DemoData.personalProgress(Instant.parse("2026-11-20T10:00:00Z"));
        assertEquals(demo, LinkedProgress.fromWire(Json.parse(Json.write(demo.toWire()))), "respecte le contrat");

        String html = LinkedProgressView.render("Demo_Aube", demo, Instant.now(), true);
        assertTrue(html.contains("Valeurs fictives"));
        assertTrue(html.contains("Pas encore classé"));
    }
}
