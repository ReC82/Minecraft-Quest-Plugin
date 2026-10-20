package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #81 — coordonnées F3 : les formulaires PNJ acceptent les décimales de l'écran F3 (743.222,
 * négatives comprises), sans pas imposé, et le serveur PlugAdmin les transmet telles quelles.
 */
class NpcCoordinatesTest {

    private static Map<String, String> form(String x, String y, String z) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("npc_id", "woodcutter_bob");
        f.put("world", "world_hub");
        f.put("x", x);
        f.put("y", y);
        f.put("z", z);
        f.put("confirm", "true");
        return f;
    }

    @Test
    void f3DecimalAndNegativeCoordinatesAreAcceptedAsTyped() {
        for (String type : List.of("npc.citizens.create", "npc.citizens.move")) {
            var v = AgentActionCatalog.validate(type, form("743.222", "67.5", "-713.699"));
            assertTrue(v.valid(), type + " : " + v.error());
            assertEquals("743.222", v.params().get("x"), "aucun arrondi au demi-bloc");
            assertEquals("67.5", v.params().get("y"));
            assertEquals("-713.699", v.params().get("z"));
        }
        var whole = AgentActionCatalog.validate("npc.citizens.create", form("743", "67", "-714"));
        assertEquals("743", whole.params().get("x"), "entier inchangé");
    }

    @Test
    void invalidCoordinatesAreStillRefused() {
        for (String[] bad : new String[][] {{"abc", "67", "0"}, {"NaN", "67", "0"}, {"1", "Infinity", "0"},
                {"", "67", "0"}, {"30000000", "67", "0"}, {"0", "5000", "0"}, {"743,222", "67", "0"}}) {
            var v = AgentActionCatalog.validate("npc.citizens.create", form(bad[0], bad[1], bad[2]));
            assertFalse(v.valid(), String.join("/", bad));
        }
    }

    @Test
    void theFormHasNoHalfBlockStepAndAcceptsAPastedF3Triple() {
        String html = AgentPages.xyzFields("npc-bob-spawn", null, false);
        assertFalse(html.contains("step=\"0.5\""), "le pas 0.5 refusait 743.222");
        assertEquals(3, html.split("step=\"any\"", -1).length - 1, "X, Y et Z");
        assertTrue(html.contains("data-xyz"), "collage F3 « x y z » (panel.js)");
        assertTrue(html.contains("Décimales acceptées"), "règle expliquée avant la saisie");
    }

    @Test
    void prefilledPositionKeepsF3Precision() {
        String html = AgentPages.xyzFields("npc-bob-move", Map.of("x", 743.2219, "y", 67.0, "z", -713.699), true);
        assertTrue(html.contains("value=\"743.222\""), html);
        assertTrue(html.contains("value=\"67\""), html);
        assertTrue(html.contains("value=\"-713.699\""), html);
        assertEquals("", AgentPages.coordInput("NaN"));
    }
}
