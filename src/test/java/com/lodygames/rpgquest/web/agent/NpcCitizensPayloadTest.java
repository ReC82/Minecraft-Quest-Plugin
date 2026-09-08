package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.Json;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Payload JSON réel de {@code npc.citizens.list} et discipline de {@code npc.citizens.link}
 * (whitelist, validation, collisions, no-op) — issue #81, phase 1.
 */
class NpcCitizensPayloadTest {

    private final AgentActionExecutor executor = new AgentActionExecutor(
            ref -> CompletableFuture.completedFuture(Optional.empty()),
            (uuid, key) -> CompletableFuture.completedFuture(Optional.empty()),
            new CitizensActions());

    private AgentActionOutcome run(String type, Map<String, String> params) {
        return executor.execute(new AgentAction("a", type, params)).join();
    }

    @Test
    void citizensListSerialisesTheRosterWithAvailability() {
        AgentActionOutcome outcome = run("npc.citizens.list", Map.of());
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        String json = Json.write(outcome.details());
        assertTrue(json.contains("\"citizens\""));
        assertTrue(json.contains("\"citizensAvailable\":true"));
        assertTrue(json.contains("\"total\":2") && json.contains("\"available\":1") && json.contains("\"linked\":1"));
        assertTrue(json.contains("\"numericId\":6") && json.contains("\"linkedNpcId\":\"guard\"")
                && json.contains("\"availableForBinding\":false"));
        assertTrue(json.contains("\"numericId\":14") && json.contains("\"name\":\"Bûcheron Bob\"")
                && json.contains("\"availableForBinding\":true"));
    }

    @Test
    void citizensLinkWhitelistedAndValidated() {
        assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob")).status());
        assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob", "citizens_id", "abc")).status());
        assertEquals(AgentActionOutcome.REJECTED, run("npc.delete.link", Map.of()).status());

        AgentActionOutcome ok = run("npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob", "citizens_id", "14"));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("LINKED", ok.value());
    }

    @Test
    void citizensLinkCollisionAndNoopBecomeReadableOutcomes() {
        AgentActionOutcome collision = run("npc.citizens.link",
                Map.of("npc_id", "woodcutter_bob", "citizens_id", "6"));
        assertEquals(AgentActionOutcome.FAILED, collision.status());
        assertEquals("CITIZENS_TAKEN", collision.value());

        AgentActionOutcome noop = run("npc.citizens.link", Map.of("npc_id", "guard", "citizens_id", "6"));
        assertEquals(AgentActionOutcome.SUCCESS, noop.status());
        assertEquals("NOOP", noop.value());
    }

    private static final class CitizensActions extends StubAgentActions {
        @Override
        public CompletableFuture<CitizensRosterView> citizensRoster() {
            return CompletableFuture.completedFuture(new CitizensRosterView(true, List.of(
                    new CitizensNpcSummary(6, "11111111-1111-1111-1111-111111111111", "Garde", "guard", false, true),
                    new CitizensNpcSummary(14, "22222222-2222-2222-2222-222222222222", "Bûcheron Bob", null, true, true)),
                    2, 1, 1));
        }

        @Override
        public CompletableFuture<MutationResult> citizensLink(String npcId, int citizensNumericId) {
            if (citizensNumericId == 6 && npcId.equals("guard")) {
                return CompletableFuture.completedFuture(new MutationResult(true, "NOOP",
                        "Citizens #6 est déjà lié à « guard ».", List.of()));
            }
            if (citizensNumericId == 6) {
                return CompletableFuture.completedFuture(new MutationResult(false, "CITIZENS_TAKEN",
                        "Citizens #6 est déjà lié à npc_id=guard.", List.of()));
            }
            return CompletableFuture.completedFuture(new MutationResult(true, "LINKED",
                    "« " + npcId + " » lié à Citizens #" + citizensNumericId + ".", List.of("Citizens #" + citizensNumericId + " <-> " + npcId)));
        }
    }
}
