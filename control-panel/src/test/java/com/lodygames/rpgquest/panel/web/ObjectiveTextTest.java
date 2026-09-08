package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lecture des objectifs de quête structurés (#78) : verbe FR + nom de cible + quantité, sans regex. */
class ObjectiveTextTest {

    private static Map<String, Object> summary(String kind, String target, Object amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("target", target);
        m.put("amount", amount);
        m.put("raw", "raw-" + kind);
        return m;
    }

    @Test
    void killEntityUsesFrenchNameAndKeepsToken() {
        ObjectiveText.Objective o = ObjectiveText.fromSummary(summary("KILL_ENTITY", "SPIDER", 5));
        assertEquals("Tuer Araignée (x5)", o.label());
        assertEquals("SPIDER", o.rawTarget());
        assertTrue(o.hasTarget());
    }

    @Test
    void collectItemUsesFrenchName() {
        assertEquals("Collecter Éclat d'améthyste (x2)",
                ObjectiveText.fromSummary(summary("COLLECT_ITEM", "AMETHYST_SHARD", 2)).label());
    }

    @Test
    void craftAndBreakAndPlaceAreCovered() {
        assertEquals("Fabriquer Épée en diamant",
                ObjectiveText.fromSummary(summary("CRAFT_ITEM", "DIAMOND_SWORD", 1)).label());
        assertEquals("Casser Bûche de chêne (x20)",
                ObjectiveText.fromSummary(summary("BREAK_BLOCK", "OAK_LOG", 20)).label());
        assertEquals("Placer Terre (x3)",
                ObjectiveText.fromSummary(summary("PLACE_BLOCK", "DIRT", 3)).label());
    }

    @Test
    void singleAmountDropsTheCountSuffix() {
        assertEquals("Tuer Araignée", ObjectiveText.fromSummary(summary("KILL_ENTITY", "SPIDER", 1)).label());
    }

    @Test
    void talkToNpcPrettifiesTheIdAndKeepsItAsTarget() {
        ObjectiveText.Objective o = ObjectiveText.fromSummary(summary("TALK_TO_NPC", "woodcutter_bob", 1));
        assertEquals("Parler à Woodcutter Bob", o.label());
        assertEquals("woodcutter_bob", o.rawTarget());
    }

    @Test
    void reachLocationUsesTheWorldName() {
        assertEquals("Se rendre dans World Nether",
                ObjectiveText.fromSummary(summary("REACH_LOCATION", "world_nether", 1)).label());
    }

    @Test
    void unknownKindFallsBackToRawAndNeverThrows() {
        Map<String, Object> m = summary("MYSTERY", null, 0);
        m.put("raw", "Faire quelque chose avec DIAMOND_SWORD");
        ObjectiveText.Objective o = ObjectiveText.fromSummary(m);
        assertTrue(o.label().contains("Épée en diamant"), o.label());
        assertFalse(o.hasTarget());
    }
}
