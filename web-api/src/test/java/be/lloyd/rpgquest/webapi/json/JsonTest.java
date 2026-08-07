package be.lloyd.rpgquest.webapi.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "RPGQuest");
        root.put("online", true);
        root.put("playerCount", 5L);
        root.put("tags", List.of("a", "b"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("x", 1L);
        root.put("nested", nested);
        root.put("empty", List.of());
        root.put("absent", null);

        String json = Json.write(root);
        Object parsed = Json.parse(json);

        assertTrue(parsed instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals("RPGQuest", map.get("name"));
        assertEquals(Boolean.TRUE, map.get("online"));
        assertEquals(5L, map.get("playerCount"));
        assertEquals(List.of("a", "b"), map.get("tags"));
        assertEquals(List.of(), map.get("empty"));
        assertTrue(map.containsKey("absent"));
    }

    @Test
    void roundTripsUnicodeAndEscapedCharacters() {
        String text = "Café ☕ 日本語 🎮 \"guillemets\" \n retour à la ligne";
        String json = Json.write(Map.of("value", text));
        Object parsed = Json.parse(json);

        assertEquals(text, ((Map<?, ?>) parsed).get("value"));
    }

    @Test
    void parsesUnicodeEscapeSequences() {
        Object parsed = Json.parse("{\"value\":\"caf\\u00e9\"}");
        assertEquals("café", ((Map<?, ?>) parsed).get("value"));
    }

    @Test
    void rejectsTruncatedJson() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":1} garbage"));
    }

    @Test
    void rejectsUnknownEscape() {
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":\"\\q\"}"));
    }

    @Test
    void parsesNumbersAsLongOrDouble() {
        Map<?, ?> parsed = (Map<?, ?>) Json.parse("{\"i\":42,\"d\":1.5,\"neg\":-3}");
        assertEquals(42L, parsed.get("i"));
        assertEquals(1.5d, parsed.get("d"));
        assertEquals(-3L, parsed.get("neg"));
    }
}
