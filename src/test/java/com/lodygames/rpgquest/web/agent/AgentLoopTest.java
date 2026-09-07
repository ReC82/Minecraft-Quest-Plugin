package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.web.admin.HealthSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Boucles de l'agent (issue #51) : heartbeat, file d'actions, backoff, idempotence — sans réseau ni
 * Bukkit. Une panne PlugAdmin ne doit jamais faire remonter d'exception.
 */
class AgentLoopTest {

    private static final UUID RONDOUDOU = UUID.fromString("00000000-0000-0000-0000-000000009000");

    private static final HealthSource HEALTH = new HealthSource() {
        @Override public String pluginName() {
            return "RPGQuest";
        }

        @Override public String pluginVersion() {
            return "9.9.9-test";
        }

        @Override public int playersOnline() {
            return 1;
        }

        @Override public int maxPlayers() {
            return 20;
        }

        @Override public long uptimeSeconds() {
            return 42;
        }

        @Override public String targetEnv() {
            return "dev";
        }

        @Override public List<WorldInfo> essentialWorlds() {
            return List.of(new WorldInfo("hub", "world_hub", true));
        }
    };

    private final AtomicInteger variableReads = new AtomicInteger();

    private AgentConfig config() {
        return new AgentConfig(true, "https://plugadmin.example", "rpgquest-dev", "dev", "tok",
                20, 15, 5000, 10000, 300, true, 256 * 1024L);
    }

    private AgentActionExecutor executor() {
        PlayerDirectory directory = ref -> CompletableFuture.completedFuture(
                Optional.of(new PlayerDirectory.ResolvedPlayer(RONDOUDOU, "Rondoudou9000")));
        PlayerVariables variables = (uuid, key) -> {
            variableReads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("false"));
        };
        return new AgentActionExecutor(directory, variables, new StubAgentActions());
    }

    private AgentLoop loop(FakeTransport transport, Instant[] clock) {
        return new AgentLoop(LoggerFactory.getLogger("test"), config(), new HeartbeatPayload(HEALTH),
                executor(), new ProcessedActionCache(), transport, () -> clock[0]);
    }

    // ---- Heartbeat -----------------------------------------------------------------

    @Test
    void heartbeatSuccessConfirmsConnectivity() {
        FakeTransport transport = new FakeTransport();
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};
        AgentLoop loop = loop(transport, clock);

        loop.heartbeatTick();

        assertEquals(1, transport.heartbeats);
        assertTrue(loop.firstHeartbeatConfirmed());
        assertEquals(0, loop.heartbeatFailures());
    }

    @Test
    void heartbeatNetworkFailureNeverThrowsAndBacksOff() {
        FakeTransport transport = new FakeTransport();
        transport.heartbeatError = () -> new PlugAdminUnavailableException("connexion refusée");
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};
        AgentLoop loop = loop(transport, clock);

        loop.heartbeatTick(); // ne lève pas
        assertEquals(1, loop.heartbeatFailures());
        assertEquals(1, transport.heartbeatAttempts);
        assertFalse(loop.firstHeartbeatConfirmed());

        // Dans la fenêtre de backoff : aucun nouvel appel réseau.
        clock[0] = clock[0].plusSeconds(2);
        loop.heartbeatTick();
        assertEquals(1, transport.heartbeatAttempts, "backoff : pas de nouvel appel");

        // Après la fenêtre de backoff + réseau rétabli : reconnexion.
        transport.heartbeatError = null;
        clock[0] = clock[0].plusSeconds(120);
        loop.heartbeatTick();
        assertEquals(2, transport.heartbeatAttempts);
        assertEquals(1, transport.heartbeats);
        assertEquals(0, loop.heartbeatFailures());
        assertTrue(loop.firstHeartbeatConfirmed());
    }

    // ---- File d'actions ----------------------------------------------------------

    @Test
    void pollExecutesVariableGetAndReturnsResult() {
        FakeTransport transport = new FakeTransport();
        transport.pending.add(new AgentAction("act-1", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1")));
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};

        loop(transport, clock).pollTick();

        assertEquals(1, variableReads.get());
        assertEquals(1, transport.results.size());
        assertEquals("act-1", transport.results.get(0).get("action_id"));
        assertEquals("SUCCESS", transport.results.get(0).get("status"));
        assertEquals("false", transport.results.get(0).get("value"));
    }

    @Test
    void duplicateActionIdIsNotReExecuted() {
        FakeTransport transport = new FakeTransport();
        AgentAction action = new AgentAction("act-dup", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1"));
        transport.pending.add(action);
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};
        AgentLoop loop = loop(transport, clock);

        loop.pollTick();
        // PlugAdmin re-livre la même action (résultat perdu) : toujours dans pending.
        clock[0] = clock[0].plusSeconds(20);
        loop.pollTick();

        assertEquals(1, variableReads.get(), "l'action ne doit être exécutée qu'une fois");
        assertEquals(2, transport.results.size(), "mais le résultat mémorisé est renvoyé à nouveau");
        assertEquals("SUCCESS", transport.results.get(1).get("status"));
    }

    @Test
    void unknownActionTypeIsRejectedNotExecuted() {
        FakeTransport transport = new FakeTransport();
        transport.pending.add(new AgentAction("act-bad", "console.exec", Map.of("cmd", "stop")));
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};

        loop(transport, clock).pollTick();

        assertEquals(0, variableReads.get());
        assertEquals("REJECTED", transport.results.get(0).get("status"));
    }

    @Test
    void pollNetworkFailureNeverThrows() {
        FakeTransport transport = new FakeTransport();
        transport.fetchError = () -> new PlugAdminUnavailableException("timeout");
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};
        AgentLoop loop = loop(transport, clock);

        loop.pollTick();
        assertEquals(1, loop.pollFailures());
        assertEquals(0, transport.results.size());
    }

    @Test
    void lostResultResponseIsRetriedWithoutReExecuting() {
        FakeTransport transport = new FakeTransport();
        transport.resultError = () -> new PlugAdminUnavailableException("connexion coupée");
        AgentAction action = new AgentAction("act-retry", "player.variable.get",
                Map.of("player", "Rondoudou9000", "key", "CLAIM_TIER_1"));
        transport.pending.add(action);
        Instant[] clock = {Instant.parse("2026-09-07T10:00:00Z")};
        AgentLoop loop = loop(transport, clock);

        loop.pollTick(); // exécute, envoi du résultat échoue
        assertEquals(1, variableReads.get());

        transport.resultError = null;
        clock[0] = clock[0].plusSeconds(20);
        loop.pollTick(); // même action re-livrée : pas de ré-exécution, renvoi du résultat

        assertEquals(1, variableReads.get());
        assertEquals(1, transport.results.size());
        assertEquals("SUCCESS", transport.results.get(0).get("status"));
    }

    // ---- Faux transport --------------------------------------------------------

    private static final class FakeTransport implements PlugAdminTransport {
        int heartbeats;
        int heartbeatAttempts;
        final List<AgentAction> pending = new ArrayList<>();
        final List<Map<String, Object>> results = new ArrayList<>();
        java.util.function.Supplier<RuntimeException> heartbeatError;
        java.util.function.Supplier<RuntimeException> fetchError;
        java.util.function.Supplier<RuntimeException> resultError;

        @Override
        public int sendHeartbeat(Map<String, Object> payload) {
            heartbeatAttempts++;
            if (heartbeatError != null) {
                throw heartbeatError.get();
            }
            heartbeats++;
            return 200;
        }

        @Override
        public List<AgentAction> fetchActions() {
            if (fetchError != null) {
                throw fetchError.get();
            }
            return new ArrayList<>(pending);
        }

        @Override
        public int sendResult(String actionId, Map<String, Object> body) {
            if (resultError != null) {
                throw resultError.get();
            }
            results.add(body);
            return 200;
        }
    }
}
