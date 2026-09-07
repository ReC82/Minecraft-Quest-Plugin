package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Exécuteur d'actions de l'agent (issue #51, phases 8/9) : whitelist stricte + service métier réel. */
class AgentActionExecutorTest {

    private static final UUID RONDOUDOU = UUID.fromString("00000000-0000-0000-0000-000000009000");

    private final PlayerDirectory directory = ref -> {
        if ("Rondoudou9000".equalsIgnoreCase(ref) || RONDOUDOU.toString().equals(ref)) {
            return CompletableFuture.completedFuture(Optional.of(new PlayerDirectory.ResolvedPlayer(RONDOUDOU, "Rondoudou9000")));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    };

    private final PlayerVariables variables = (uuid, key) -> {
        if (RONDOUDOU.equals(uuid) && "CLAIM_TIER_1".equals(key)) {
            return CompletableFuture.completedFuture(Optional.of("false"));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    };

    private final AgentActionExecutor executor = new AgentActionExecutor(directory, variables);

    private AgentActionOutcome run(AgentAction action) {
        return executor.execute(action).join();
    }

    @Test
    void unknownTypeIsRejectedWithoutExecuting() {
        AgentActionOutcome outcome = run(new AgentAction("a1", "server.shutdown", Map.of()));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
        assertTrue(outcome.message().contains("non whitelisté"));
    }

    @Test
    void missingKeyIsRejected() {
        AgentActionOutcome outcome = run(new AgentAction("a2", "player.variable.get", Map.of("player", "Rondoudou9000")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
    }

    @Test
    void missingPlayerIsRejected() {
        AgentActionOutcome outcome = run(new AgentAction("a3", "player.variable.get", Map.of("key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.REJECTED, outcome.status());
    }

    @Test
    void unknownPlayerFails() {
        AgentActionOutcome outcome = run(new AgentAction("a4", "player.variable.get",
                Map.of("player", "GhostPlayer", "key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.FAILED, outcome.status());
        assertTrue(outcome.message().contains("Joueur inconnu"));
    }

    @Test
    void readsExistingVariable() {
        AgentActionOutcome outcome = run(new AgentAction("a5", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("false", outcome.value());
        assertEquals(Boolean.TRUE, outcome.details().get("present"));
        assertEquals("Rondoudou9000", outcome.details().get("player_name"));
    }

    @Test
    void absentVariableIsStillSuccessWithNullValue() {
        AgentActionOutcome outcome = run(new AgentAction("a6", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "NEVER_SET")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertNull(outcome.value());
        assertEquals(Boolean.FALSE, outcome.details().get("present"));
    }

    @Test
    void acceptsUuidDirectly() {
        AgentActionOutcome outcome = run(new AgentAction("a7", "player.variable.get",
                Map.of("player_uuid", RONDOUDOU.toString(), "key", "CLAIM_TIER_1")));
        assertEquals(AgentActionOutcome.SUCCESS, outcome.status());
        assertEquals("false", outcome.value());
    }
}
