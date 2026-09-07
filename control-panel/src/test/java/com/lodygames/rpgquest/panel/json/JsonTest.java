package com.lodygames.rpgquest.panel.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedObjectsArraysAndScalars() {
        String json = """
                {"a":1,"b":2.5,"c":true,"d":null,"e":"x\\ny","f":[1,"two",{"g":false}]}
                """;
        Map<String, Object> obj = Json.parseObject(json);
        assertEquals(1L, obj.get("a"));
        assertEquals(2.5, obj.get("b"));
        assertEquals(Boolean.TRUE, obj.get("c"));
        assertTrue(obj.containsKey("d") && obj.get("d") == null);
        assertEquals("x\ny", obj.get("e"));
        List<?> list = assertInstanceOf(List.class, obj.get("f"));
        assertEquals(3, list.size());
        assertEquals(Boolean.FALSE, ((Map<?, ?>) list.get(2)).get("g"));
    }

    @Test
    void writeRoundTripsAMap() {
        String written = Json.write(Map.of("k", "v"));
        assertEquals(Map.of("k", "v"), Json.parseObject(written));
    }

    @Test
    void parseObjectRejectsNonObjects() {
        assertThrows(JsonParseException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(JsonParseException.class, () -> Json.parse("{bad}"));
        assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":1} trailing"));
    }
}
