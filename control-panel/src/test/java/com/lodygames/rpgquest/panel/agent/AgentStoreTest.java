package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistance agents dans {@code control-panel.db} (issue #51, phase 6). */
class AgentStoreTest {

    private AgentStore store;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path tmp) {
        store = new AgentStore(tmp.resolve("cp.db").toString());
    }

    private HeartbeatRecord heartbeat(String agentId, Instant at, String version) {
        return new HeartbeatRecord(agentId, "dev", at, at.toString(), "agent/v1", "RPGQuest", version,
                "ONLINE", 3, 20, 3600, "{\"hub\":{\"name\":\"world_hub\",\"loaded\":true}}", "{}");
    }

    @Test
    void heartbeatIsUpsertedAndReadBack() {
        store.saveHeartbeat(heartbeat("rpgquest-dev", Instant.parse("2026-09-07T10:00:00Z"), "0.1"));
        store.saveHeartbeat(heartbeat("rpgquest-dev", Instant.parse("2026-09-07T10:00:20Z"), "0.2"));
        HeartbeatRecord latest = store.latestHeartbeat("rpgquest-dev").orElseThrow();
        assertEquals("0.2", latest.pluginVersion());
        assertEquals(3, latest.playersOnline());
        assertTrue(store.latestHeartbeat("other").isEmpty());
    }

    @Test
    void pendingActionIsDeliveredThenKeptUntilResult() {
        String id = store.createAction("rpgquest-dev", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1"), "owner");
        Instant now = Instant.parse("2026-09-07T10:00:00Z");

        List<AgentActionRow> first = store.deliverableActions("rpgquest-dev", now, Duration.ofMinutes(5));
        assertEquals(1, first.size());
        assertEquals(id, first.get(0).id());

        // Toujours livrable tant qu'aucun résultat n'est arrivé (idempotence côté panel).
        List<AgentActionRow> second = store.deliverableActions("rpgquest-dev", now.plusSeconds(20), Duration.ofMinutes(5));
        assertEquals(1, second.size());
        assertEquals(AgentActionStatus.DELIVERED, second.get(0).status());
        assertEquals(2, second.get(0).deliverCount());
    }

    @Test
    void actionForOtherAgentIsNotDelivered() {
        store.createAction("rpgquest-dev", "player.variable.get", Map.of("key", "X"), "owner");
        assertTrue(store.deliverableActions("rpgquest-staging", Instant.now(), Duration.ofMinutes(5)).isEmpty());
    }

    @Test
    void resultMakesActionTerminalAndIsIdempotent() {
        String id = store.createAction("rpgquest-dev", "player.variable.get", Map.of("key", "CLAIM_TIER_1"), "owner");
        Instant now = Instant.parse("2026-09-07T10:00:00Z");
        store.deliverableActions("rpgquest-dev", now, Duration.ofMinutes(5));

        assertTrue(store.recordResult(id, "rpgquest-dev", AgentActionStatus.SUCCESS, "false", "ok", "{}", now.plusSeconds(1)));
        AgentActionRow row = store.action(id).orElseThrow();
        assertEquals(AgentActionStatus.SUCCESS, row.status());
        assertEquals("false", row.resultValue());

        // Renvoi du même résultat (réponse perdue) : accepté, sans re-traitement.
        assertTrue(store.recordResult(id, "rpgquest-dev", AgentActionStatus.SUCCESS, "false", "ok", "{}", now.plusSeconds(9)));
        assertEquals(now.plusSeconds(1), store.action(id).orElseThrow().completedAt());

        // Plus renvoyée dans la file.
        assertTrue(store.deliverableActions("rpgquest-dev", now.plusSeconds(30), Duration.ofMinutes(5)).isEmpty());
    }

    @Test
    void resultForWrongAgentIsRefused() {
        String id = store.createAction("rpgquest-dev", "player.variable.get", Map.of("key", "X"), "owner");
        assertFalse(store.recordResult(id, "rpgquest-staging", AgentActionStatus.SUCCESS, "v", "m", "{}", Instant.now()));
        assertEquals(AgentActionStatus.PENDING, store.action(id).orElseThrow().status());
    }

    @Test
    void staleActionExpires() {
        String id = store.createAction("rpgquest-dev", "player.variable.get", Map.of("key", "X"), "owner");
        Instant later = Instant.now().plus(Duration.ofMinutes(10));
        List<AgentActionRow> deliverable = store.deliverableActions("rpgquest-dev", later, Duration.ofMinutes(5));
        assertTrue(deliverable.isEmpty());
        assertEquals(AgentActionStatus.EXPIRED, store.action(id).orElseThrow().status());
    }

    @Test
    void latestSuccessfulActionOfTypeIgnoresNewerNonTerminalOnes() throws InterruptedException {
        // Un premier story.list réussi (catalogue exploitable).
        String ok = store.createAction("rpgquest-dev", "story.list", Map.of(), "owner");
        store.recordResult(ok, "rpgquest-dev", AgentActionStatus.SUCCESS, "2", "2 story(s).",
                "{\"details\":{\"stories\":[]}}", Instant.now());
        Thread.sleep(1100); // created_at est tronqué à la seconde

        // Une action « Rafraîchir » plus récente, encore PENDING.
        String pending = store.createAction("rpgquest-dev", "story.list", Map.of(), "owner");

        // latestActionOfType renvoie la plus récente (PENDING) — d'où le bug d'affichage.
        assertEquals(pending, store.latestActionOfType("rpgquest-dev", "story.list").orElseThrow().id());
        // latestSuccessfulActionOfType saute la PENDING et garde le dernier SUCCESS exploitable.
        AgentActionRow row = store.latestSuccessfulActionOfType("rpgquest-dev", "story.list").orElseThrow();
        assertEquals(ok, row.id());
        assertEquals(AgentActionStatus.SUCCESS, row.status());

        assertTrue(store.latestSuccessfulActionOfType("rpgquest-dev", "quest.list").isEmpty());
    }
}
