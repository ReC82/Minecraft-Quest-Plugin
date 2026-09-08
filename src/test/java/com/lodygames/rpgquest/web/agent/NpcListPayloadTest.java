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
 * Sérialisation réelle du payload {@code npc.list} (V2) : whitelist, structure {@code details},
 * séparation définition logique / binding Citizens, anomalies transportées telles quelles.
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
    void npcListSeparatesLogicalDefinitionFromCitizensBinding() {
        AgentActionOutcome outcome = run("npc.list");
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());

        String json = Json.write(outcome.details());
        assertTrue(json.contains("\"npcs\""));
        assertTrue(json.contains("\"canonicalIds\":[\"guard\",\"woodcutter_bob\"]"), json);
        assertTrue(json.contains("\"definedIds\":[\"guard\"]"), json);
        assertTrue(json.contains("\"citizensAvailable\":true"));
        assertTrue(json.contains("\"withDefinition\":1") && json.contains("\"withoutDefinition\":1"));

        // guard : définition + binding -> LINKED, aucune anomalie
        assertTrue(json.contains("\"id\":\"guard\"") && json.contains("\"displayName\":\"Garde\""));
        assertTrue(json.contains("\"logicalDefinitionPresent\":true") && json.contains("\"citizensBindingPresent\":true"));
        assertTrue(json.contains("\"state\":\"LINKED\""));
        assertTrue(json.contains("\"role\":\"quest_giver\""));

        // woodcutter_bob : référencé par une quête, aucune définition -> erreur de contenu, état UNDEFINED_REFERENCE
        assertTrue(json.contains("\"id\":\"woodcutter_bob\""));
        assertTrue(json.contains("\"logicalDefinitionPresent\":false") && json.contains("\"state\":\"UNDEFINED_REFERENCE\""));
        assertTrue(json.contains("\"code\":\"NO_DEFINITION\"") && json.contains("\"severity\":\"error\""));
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
            NpcSummary guard = new NpcSummary("guard", "Garde", true, true, 7, 1, true,
                    "Garde du village", "quest_giver", "rpgquest:guard", true, "rpgquest:guard", 6, 9,
                    List.of("rpgquest:first_steps"), List.of("rpgquest:crystal_hunt"),
                    List.of("rpgquest:crystal_hunt"),
                    List.of("DEFINITION", "BINDING", "DIALOGUE", "QUEST_GIVER", "QUEST_TALK"), "LINKED", List.of());
            NpcSummary woodcutter = new NpcSummary("woodcutter_bob", null, false, false, null, 0, true,
                    null, null, null, false, null, 0, 0,
                    List.of(), List.of(), List.of("rpgquest:woodcutters_request"), List.of("QUEST_TALK"),
                    "UNDEFINED_REFERENCE",
                    List.of(new NpcWarning("NO_DEFINITION", "error",
                            "Aucune définition logique RPGQuest pour « woodcutter_bob » (référencé par objectif « parler à »). À migrer : créer la définition.")));
            return CompletableFuture.completedFuture(new NpcCatalogView(
                    List.of(woodcutter, guard), List.of("guard", "woodcutter_bob"), List.of("guard"),
                    true, 2, 1, 1, 1, 1));
        }
    }
}
