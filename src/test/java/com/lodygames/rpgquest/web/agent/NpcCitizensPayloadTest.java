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

    @Test
    void citizensCreateWhitelistedValidatedAndStructured() {
        // paramètres manquants / invalides -> REJECTED, jamais d'appel métier
        assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.create",
                Map.of("world", "world_hub", "x", "1", "y", "64", "z", "2")).status());
        assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.create",
                Map.of("npc_id", "woodcutter_bob", "world", "bad world", "x", "1", "y", "64", "z", "2")).status());
        assertEquals(AgentActionOutcome.REJECTED, run("npc.citizens.create",
                Map.of("npc_id", "woodcutter_bob", "world", "world_hub", "x", "Infinity", "y", "64", "z", "2")).status());

        AgentActionOutcome ok = run("npc.citizens.create", Map.of("npc_id", "woodcutter_bob",
                "world", "world_hub", "x", "125.5", "y", "64", "z", "-82.5", "yaw", "90", "pitch", "0"));
        assertEquals(AgentActionOutcome.SUCCESS, ok.status());
        assertEquals("31", ok.value());
        String json = Json.write(ok.details());
        assertTrue(json.contains("\"code\":\"CREATED\""));
        assertTrue(json.contains("\"citizens_id\":31"));
        assertTrue(json.contains("\"npc_id\":\"woodcutter_bob\""));
        assertTrue(json.contains("\"rolled_back\":false"));
    }

    @Test
    void citizensCreateRollbackIsAReadableFailedOutcome() {
        AgentActionOutcome out = run("npc.citizens.create", Map.of("npc_id", "rollback_me",
                "world", "world_hub", "x", "1", "y", "64", "z", "2"));
        assertEquals(AgentActionOutcome.FAILED, out.status());
        assertEquals("BIND_FAILED_ROLLED_BACK", out.value());
        assertTrue(Json.write(out.details()).contains("\"rolled_back\":true"));
    }

    @Test
    void citizensCreateRejectionWithoutCitizensIdStillProducesAValidOutcome() {
        // Régression : citizens_id null + Map.copyOf des détails -> NPE si non géré.
        AgentActionOutcome out = run("npc.citizens.create", Map.of("npc_id", "ghost_id",
                "world", "world_hub", "x", "1", "y", "64", "z", "2"));
        assertEquals(AgentActionOutcome.FAILED, out.status());
        assertEquals("UNKNOWN_NPC", out.value());
        String json = Json.write(out.details());
        assertTrue(json.contains("\"citizens_id\":-1"), json);
        assertTrue(json.contains("\"rolled_back\":false"));
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

        @Override
        public CompletableFuture<CitizensCreateResult> citizensCreate(String npcId, String world,
                                                                      double x, double y, double z,
                                                                      float yaw, float pitch) {
            if (npcId.equals("rollback_me")) {
                return CompletableFuture.completedFuture(new CitizensCreateResult(false,
                        "BIND_FAILED_ROLLED_BACK", "Liaison impossible — PNJ #31 supprimé (rollback effectué).",
                        31, npcId, List.of(), true));
            }
            if (npcId.equals("ghost_id")) {
                // Refus AVANT création : aucun citizens_id (null) — l'outcome ne doit pas casser.
                return CompletableFuture.completedFuture(CitizensCreateResult.reject(npcId, "UNKNOWN_NPC",
                        "Aucune définition logique « ghost_id »."));
            }
            return CompletableFuture.completedFuture(new CitizensCreateResult(true, "CREATED",
                    "« " + npcId + " » créé et lié à Citizens #31 — " + world + ".",
                    31, npcId, List.of("Citizens #31 spawné dans " + world, "binding " + npcId + " <-> Citizens #31"),
                    false));
        }
    }
}
