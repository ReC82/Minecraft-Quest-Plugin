package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.Json;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Sérialisation réelle du payload {@code npc.list} : whitelist, structure {@code details}, et
 * anomalies de configuration transportées telles quelles (le contrat testé est le JSON, pas
 * seulement les objets Java).
 */
class NpcListPayloadTest {

    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()),
            new NpcActions());

    private AgentActionOutcome run(String type) {
        return executor.execute(new AgentAction("a", type, Map.of())).join();
    }

    @Test
    void npcListIsWhitelistedAndReturnsStructuredCatalog() {
        AgentActionOutcome outcome = run("npc.list");
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());

        String json = Json.write(outcome.details());
        assertTrue(json.contains("\"npcs\""));
        assertTrue(json.contains("\"canonicalIds\":[\"guard\"]"), json);
        assertTrue(json.contains("\"citizensAvailable\":true"));
        assertTrue(json.contains("\"total\":2") && json.contains("\"withWarnings\":1"));

        // PNJ correctement bindé : nom lisible, id Citizens, aucune anomalie
        assertTrue(json.contains("\"id\":\"guard\"") && json.contains("\"displayName\":\"Garde\""));
        assertTrue(json.contains("\"citizensNumericId\":7"));
        assertTrue(json.contains("\"questsGiven\":[\"rpgquest:crystal_hunt\"]"));
        assertTrue(json.contains("\"questsReferenced\":[\"rpgquest:crystal_hunt\"]"));
        assertTrue(json.contains("\"dialogueId\":\"rpgquest:guard\""));

        // ID inconnu / tag inutilisé : anomalie sérialisée avec sévérité + suggestion
        assertTrue(json.contains("\"code\":\"TAGGED_UNUSED\"") && json.contains("\"severity\":\"info\""));
        assertTrue(json.contains("Id canonique proche"));
    }

    @Test
    void emptyCatalogIsStillSuccess() {
        AgentActionOutcome outcome = new AgentActionExecutor(
                ref -> CompletableFuture.completedFuture(Optional.empty()),
                (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()),
                new StubAgentActions())
                .execute(new AgentAction("e", "npc.list", Map.of())).join();
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        String json = Json.write(outcome.details());
        assertTrue(json.contains("\"npcs\":[]"));
        assertFalse(json.contains("\"code\""));
    }

    @Test
    void unknownTypeStillRejected() {
        assertEquals(AgentActionOutcome.REJECTED, run("npc.delete").status());
    }

    private static final class NpcActions extends StubAgentActions {
        @Override
        public CompletableFuture<NpcCatalogView> npcDefinitions() {
            NpcSummary guard = new NpcSummary("guard", "Garde", 7, 1, true, true, "rpgquest:guard", 6, 9,
                    List.of("rpgquest:first_steps"), List.of("rpgquest:crystal_hunt"),
                    List.of("rpgquest:crystal_hunt"),
                    List.of("BINDING", "DIALOGUE", "QUEST_GIVER", "QUEST_TALK"), List.of());
            NpcSummary garde = new NpcSummary("garde", null, 3, 1, true, false, null, 0, 0,
                    List.of(), List.of(), List.of(), List.of("BINDING"),
                    List.of(new NpcWarning("TAGGED_UNUSED", "info",
                            "PNJ tagué « garde » mais aucun dialogue ni quête ne l'utilise. Id canonique proche : « guard » ?")));
            return CompletableFuture.completedFuture(new NpcCatalogView(
                    List.of(garde, guard), List.of("guard"), true, 2, 2, 0, 1));
        }
    }
}
