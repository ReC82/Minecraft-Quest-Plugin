package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Issue #79 — rendu de « État des quêtes d'un joueur » sur des résultats {@code quest.player.status}
 * RÉELS de DEV (anonymisés) : le relevé du 20/10 (joueur connecté, objectifs transmis) et celui du
 * 23/10 (joueur hors ligne, JAR antérieur au correctif : aucune étape transmise).
 */
class QuestPlayerStatusRenderTest {

    /** Noms en vigueur sur DEV au 23/10 (relevé npc.list), MiniMessage compris. */
    private static final Map<String, String> NPC_NAMES = Map.of(
            "guide", "<dark_green><bold>Elias</bold></dark_green>",
            "guard", "Gauthier",
            "encounter_merchant", "Marchand égaré");

    @Test
    void realOnlinePayloadRendersObjectivesCountersAndCurrentNpcNames() throws IOException {
        Map<String, String> rows = renderAll("online-20261020.json");

        String guard = rows.get("rpgquest:guard_tier2");
        assertTrue(guard.contains("Tuer Araignée (x10)"), guard);
        assertTrue(guard.contains("10/10"), guard);
        assertTrue(guard.contains("1/10"), guard);
        assertTrue(guard.contains(">fait<"), guard);

        String kit = rows.get("rpgquest:kit_tier3");
        assertTrue(kit.contains("à Elias"), "nom actuel du PNJ, MiniMessage retiré : " + kit);
        assertFalse(kit.contains("à Guide"), kit);
        assertTrue(kit.contains("1/4"), kit);

        assertTrue(rows.get("rpgquest:daily_demo_mineur").contains("6/32"));
    }

    @Test
    void realOfflinePayloadFromAnOldAgentSaysTheDetailIsMissingInsteadOfADash() throws IOException {
        Map<String, String> rows = renderAll("offline-20261023.json");

        String guard = rows.get("rpgquest:guard_tier2");
        assertTrue(guard.contains("détail non transmis par le serveur"), guard);
        // Une quête non commencée n'a légitimement aucun détail.
        assertTrue(rows.get("rpgquest:encounter_smith_ore").contains(">—<"), rows.get("rpgquest:encounter_smith_ore"));
    }

    @Test
    void statesAreShownInFrenchWithTheTechnicalCodeKeptInTheTooltip() throws IOException {
        Map<String, String> rows = renderAll("offline-20261023.json");

        String active = rows.get("rpgquest:guard_tier2");
        assertTrue(active.contains("En cours"), active);
        assertTrue(active.contains("title=\"ACTIVE\""), active);
        assertTrue(rows.get("rpgquest:guard_tier1").contains("Terminée"));
        assertTrue(rows.get("rpgquest:encounter_smith_ore").contains("Non commencée"));
        assertTrue(rows.get("rpgquest:daily_demo_bucheron").contains("Abandonnée"));
        assertEquals("<span class=\"pill pill--neutral\"><span class=\"pill-g\" aria-hidden=\"true\">•</span> WEIRD</span>",
                Ui.stateBadge("weird"), "un état inconnu reste affiché tel quel");
    }

    @Test
    void unknownNpcFallsBackToTheHumanizedIdentifier() {
        Map<String, Object> row = Map.of("kind", "TALK_TO_NPC", "target", "encounter_bandit", "amount", 1,
                "description", "Parler à encounter_bandit");
        assertEquals("Parler à Encounter Bandit", ObjectiveText.fromStatus(row, id -> id, id -> null).label());
        assertEquals("Parler à Gauthier",
                ObjectiveText.fromStatus(Map.of("kind", "TALK_TO_NPC", "target", "guard", "amount", 1),
                        id -> id, NPC_NAMES::get).label());
    }

    private static Map<String, String> renderAll(String fixture) throws IOException {
        Map<String, Object> result = Json.parseObject(read(fixture));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quests = (List<Map<String, Object>>) details.get("quests");
        Function<String, String> cities = id -> id;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> q : quests) {
            out.put(String.valueOf(q.get("questId")), AgentPages.renderQuestPlayerRow(q, cities, NPC_NAMES::get));
        }
        return out;
    }

    private static String read(String fixture) throws IOException {
        try (InputStream in = QuestPlayerStatusRenderTest.class
                .getResourceAsStream("/fixtures/quest-player-status/" + fixture)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
