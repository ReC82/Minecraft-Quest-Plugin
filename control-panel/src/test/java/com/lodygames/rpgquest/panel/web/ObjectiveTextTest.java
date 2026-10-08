package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
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

    // ---- Remise d'objets à un PNJ (issue #123) ------------------------------------------------

    @Test
    void deliverItemNamesBothTheItemAndTheReceivingNpc() {
        Map<String, Object> m = summary("DELIVER_ITEM_TO_NPC", "LEATHER", 4);
        m.put("npc", "woodcutter_bob");

        ObjectiveText.Objective o = ObjectiveText.fromSummary(m);

        assertEquals("Rapporter Cuir (x4) à Woodcutter Bob", o.label());
        assertEquals("LEATHER", o.rawTarget(), "la cible technique reste l'objet compté");
    }

    @Test
    void deliverItemOfASingleUnitOmitsTheCount() {
        Map<String, Object> m = summary("DELIVER_ITEM_TO_NPC", "STICK", 1);
        m.put("npc", "guard");

        // L'id du PNJ est seulement embelli (« guard » -> « Guard ») : le panel ne dispose pas ici
        // de son nom affiché, et inventer « Garde » serait une correspondance en dur.
        assertEquals("Rapporter Bâton à Guard", ObjectiveText.fromSummary(m).label());
    }

    /**
     * Un agent déployé plus ancien n'envoie pas encore « npc », et une définition peut réellement
     * en être dépourvue : le libellé doit rester lisible ET explicable — « à ? » laissait croire à
     * un défaut d'affichage (c'est exactement ce qu'il a fait en recette TC-257).
     */
    @Test
    void deliverItemWithoutAnNpcFieldSaysTheReceiverIsUndefined() {
        ObjectiveText.Objective o = ObjectiveText.fromSummary(summary("DELIVER_ITEM_TO_NPC", "LEATHER", 4));

        assertEquals("Rapporter Cuir (x4) à (PNJ non défini)", o.label());
    }

    @Test
    void smeltItemUsesTheObtainedItemName() {
        assertEquals("Cuire Teinture verte (x2)",
                ObjectiveText.fromSummary(summary("SMELT_ITEM", "GREEN_DYE", 2)).label());
    }

    /**
     * Issue #185 : la portée ET la règle de comptage apparaissent dans le libellé — sans elles, deux
     * objectifs attendant des actions différentes s'afficheraient à l'identique.
     */
    @Test
    void discoverWaypointStatesItsScopeAndCountingRule() {
        Map<String, Object> m = summary("DISCOVER_WAYPOINT", null, 5);
        m.put("worlds", List.of("world_hub", "wild"));
        m.put("countMode", "NEW_ONLY");

        assertEquals("Découvrir 5 waypoint(s) — world_hub, wild — nouvelles découvertes",
                ObjectiveText.fromSummary(m).label());
    }

    /** Sans filtre de monde, la portée est dite explicitement, jamais laissée vide. */
    @Test
    void discoverWaypointWithoutWorldFilterSaysAllWorlds() {
        Map<String, Object> m = summary("DISCOVER_WAYPOINT", null, 3);
        m.put("countMode", "INCLUDE_EXISTING");

        assertEquals("Découvrir 3 waypoint(s) — tous mondes — découvertes déjà acquises incluses",
                ObjectiveText.fromSummary(m).label());
    }

    /**
     * Le brouillon relu depuis le YAML porte « a, b » en texte, le relevé runtime une liste : les
     * deux origines doivent produire exactement le même libellé (même classe de défaut que le PNJ
     * destinataire perdu de #123).
     */
    @Test
    void discoverWaypointRendersTheSameFromSourceTextAndRuntimeList() {
        Map<String, Object> fromSource = summary("DISCOVER_WAYPOINT", null, 2);
        fromSource.put("worlds", "world_hub, wild");
        fromSource.put("countMode", "NEW_ONLY");
        Map<String, Object> fromRuntime = summary("DISCOVER_WAYPOINT", null, 2);
        fromRuntime.put("worlds", List.of("world_hub", "wild"));
        fromRuntime.put("countMode", "NEW_ONLY");

        assertEquals(ObjectiveText.fromSummary(fromRuntime).label(),
                ObjectiveText.fromSummary(fromSource).label());
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
