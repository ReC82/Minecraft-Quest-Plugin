package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contrat {@code /agent/v1/*} de bout en bout (issue #51, phases 3/9/12/16). */
class AgentEndpointsTest {

    private static final String DEV_TOKEN = "dev-token-aaaaaaaaaaaaaaaa";
    private static final String STG_TOKEN = "stg-token-bbbbbbbbbbbbbbbb";

    private HttpServer server;
    private AgentStore store;
    private InMemoryAuditLog audit;
    private final AtomicBoolean disabled = new AtomicBoolean(false);
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path tmp) throws Exception {
        store = new AgentStore(tmp.resolve("cp.db").toString());
        audit = new InMemoryAuditLog();
        AgentRegistry registry = new AgentRegistry(List.of(
                new AgentIdentity("rpgquest-dev", "dev", DEV_TOKEN),
                new AgentIdentity("rpgquest-staging", "staging", STG_TOKEN)));
        AgentEndpoints endpoints = new AgentEndpoints(registry, store, audit, Duration.ofMinutes(5), disabled::get);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoints.register(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpResponse<String> req(String method, String path, String agentId, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (agentId != null) {
            b.header("X-Agent-Id", agentId);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String heartbeatBody(String agentId) {
        return "{\"protocol\":\"agent/v1\",\"agent_id\":\"" + agentId + "\",\"environment\":\"dev\","
                + "\"plugin\":{\"name\":\"RPGQuest\",\"version\":\"9.9.9\"},"
                + "\"server\":{\"state\":\"ONLINE\",\"players_online\":2,\"max_players\":20,\"uptime_seconds\":3600},"
                + "\"worlds\":{\"hub\":{\"name\":\"world_hub\",\"loaded\":true}},"
                + "\"generated_at\":\"2026-09-07T10:00:00Z\"}";
    }

    // ---- Auth ------------------------------------------------------------------

    @Test
    void heartbeatWithoutTokenIsRejected() throws Exception {
        assertEquals(401, req("POST", "/agent/v1/heartbeat", "rpgquest-dev", null, heartbeatBody("rpgquest-dev")).statusCode());
    }

    @Test
    void heartbeatWithWrongTokenIsRejected() throws Exception {
        assertEquals(401, req("POST", "/agent/v1/heartbeat", "rpgquest-dev", "nope", heartbeatBody("rpgquest-dev")).statusCode());
    }

    @Test
    void heartbeatForUnknownAgentIsRejected() throws Exception {
        assertEquals(401, req("POST", "/agent/v1/heartbeat", "ghost", DEV_TOKEN, heartbeatBody("ghost")).statusCode());
    }

    @Test
    void heartbeatWithMismatchedBodyAgentIsRejected() throws Exception {
        assertEquals(400, req("POST", "/agent/v1/heartbeat", "rpgquest-dev", DEV_TOKEN, heartbeatBody("rpgquest-staging")).statusCode());
    }

    // ---- Heartbeat ----------------------------------------------------------

    @Test
    void validHeartbeatIsStored() throws Exception {
        HttpResponse<String> res = req("POST", "/agent/v1/heartbeat", "rpgquest-dev", DEV_TOKEN, heartbeatBody("rpgquest-dev"));
        assertEquals(200, res.statusCode());
        HeartbeatRecord stored = store.latestHeartbeat("rpgquest-dev").orElseThrow();
        assertEquals("9.9.9", stored.pluginVersion());
        assertEquals(2, stored.playersOnline());
        assertEquals("agent/v1", stored.protocol());
        assertFalse(res.body().contains(DEV_TOKEN), "aucun secret dans la réponse");
    }

    @Test
    void oversizedHeartbeatIsRejected() throws Exception {
        // La borne de corps entrant est passée de 64 Kio à 1 Mio (issue #164 : les relevés
        // « dialogue.list » la frôlaient déjà). Elle reste FINIE — c'est ce que ce test garantit.
        String huge = "{\"protocol\":\"agent/v1\",\"pad\":\"" + "x".repeat(1_100_000) + "\"}";
        assertEquals(413, req("POST", "/agent/v1/heartbeat", "rpgquest-dev", DEV_TOKEN, huge).statusCode());
    }

    @Test
    void killSwitchReturns503() throws Exception {
        disabled.set(true);
        assertEquals(503, req("POST", "/agent/v1/heartbeat", "rpgquest-dev", DEV_TOKEN, heartbeatBody("rpgquest-dev")).statusCode());
    }

    // ---- File d'actions + résultat ---------------------------------------

    @Test
    void actionsAreScopedToTheAuthenticatedAgent() throws Exception {
        String id = store.createAction("rpgquest-dev", "player.variable.get", java.util.Map.of("key", "CLAIM_TIER_1"), "owner");

        HttpResponse<String> devPoll = req("GET", "/agent/v1/actions", "rpgquest-dev", DEV_TOKEN, null);
        assertEquals(200, devPoll.statusCode());
        assertTrue(devPoll.body().contains(id));

        HttpResponse<String> stgPoll = req("GET", "/agent/v1/actions", "rpgquest-staging", STG_TOKEN, null);
        assertEquals(200, stgPoll.statusCode());
        assertFalse(stgPoll.body().contains(id), "l'action DEV ne doit pas fuir vers staging");
    }

    @Test
    void resultIsRecordedAndIdempotent() throws Exception {
        String id = store.createAction("rpgquest-dev", "player.variable.get", java.util.Map.of("key", "CLAIM_TIER_1"), "owner");
        req("GET", "/agent/v1/actions", "rpgquest-dev", DEV_TOKEN, null);

        String body = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"false\",\"message\":\"ok\"}";
        assertEquals(200, req("POST", "/agent/v1/actions/" + id + "/result", "rpgquest-dev", DEV_TOKEN, body).statusCode());
        assertEquals(AgentActionStatus.SUCCESS, store.action(id).orElseThrow().status());
        assertEquals("false", store.action(id).orElseThrow().resultValue());

        // Rejeu (réponse perdue) : toujours 200, action inchangée.
        assertEquals(200, req("POST", "/agent/v1/actions/" + id + "/result", "rpgquest-dev", DEV_TOKEN, body).statusCode());
        assertTrue(audit.recent(20).stream().anyMatch(e -> e.action().equals("agent.action.result")));
    }

    /**
     * Issue #164 — régression qui cassait le catalogue des dialogues. Un corps de résultat
     * volumineux était stocké coupé à 20 000 caractères, donc <strong>au milieu du JSON</strong> :
     * toute page relisant {@code result_json} perdait l'intégralité du relevé en silence (l'action
     * restait {@code SUCCESS}). Le cas réel : {@code dialogue.list} de 10 dialogues, dont
     * {@code rpgquest:jeff}, qui disparaissait du catalogue.
     */
    @Test
    void largeResultBodyStaysParsableJson() throws Exception {
        String id = store.createAction("rpgquest-dev", "dialogue.list", java.util.Map.of(), "owner");
        req("GET", "/agent/v1/actions", "rpgquest-dev", DEV_TOKEN, null);

        // Corps réaliste (~60 000 caractères) : largement au-delà de l'ancienne borne de 20 000.
        StringBuilder dialogues = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                dialogues.append(',');
            }
            dialogues.append("{\"id\":\"rpgquest:d").append(i).append("\",\"key\":\"d").append(i)
                    .append("\",\"text\":\"").append("x".repeat(180)).append("\"}");
        }
        String body = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"300\","
                + "\"message\":\"ok\",\"details\":{\"dialogues\":[" + dialogues + "]}}";
        assertTrue(body.length() > 20_000, "le corps doit dépasser l'ancienne borne");

        assertEquals(200, req("POST", "/agent/v1/actions/" + id + "/result", "rpgquest-dev", DEV_TOKEN, body)
                .statusCode());

        String stored = store.action(id).orElseThrow().resultJson();
        assertNotNull(stored, "le corps doit être conservé");
        // Le point qui comptait : ce qui est stocké se relit. Avant le correctif, ce parse échouait.
        Object details = com.lodygames.rpgquest.panel.json.Json.parseObject(stored).get("details");
        assertTrue(details instanceof java.util.Map, "les détails doivent rester exploitables");
        Object list = ((java.util.Map<?, ?>) details).get("dialogues");
        assertTrue(list instanceof java.util.List, "la liste de dialogues doit survivre");
        assertEquals(300, ((java.util.List<?>) list).size(), "aucun dialogue perdu");
    }

    /**
     * Au-delà de la borne (très large) on ne conserve plus un fragment illisible : on stocke un
     * objet JSON <em>valide</em> qui déclare le dépassement, pour que l'interface puisse le dire au
     * lieu de conclure à tort à une absence de contenu.
     */
    @Test
    void oversizedResultBodyIsReplacedByAValidTruncationMarker() throws Exception {
        String id = store.createAction("rpgquest-dev", "dialogue.list", java.util.Map.of(), "owner");
        req("GET", "/agent/v1/actions", "rpgquest-dev", DEV_TOKEN, null);

        String body = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":{\"blob\":\""
                + "y".repeat(600 * 1024) + "\"}}";
        assertEquals(200, req("POST", "/agent/v1/actions/" + id + "/result", "rpgquest-dev", DEV_TOKEN, body)
                .statusCode());

        String stored = store.action(id).orElseThrow().resultJson();
        var parsed = com.lodygames.rpgquest.panel.json.Json.parseObject(stored); // ne doit pas lever
        assertEquals(Boolean.TRUE, parsed.get("truncated"), "le dépassement doit être déclaré : " + stored);
    }

    @Test
    void resultForActionOfAnotherAgentIsRejected() throws Exception {
        String id = store.createAction("rpgquest-dev", "player.variable.get", java.util.Map.of("key", "X"), "owner");
        String body = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\"}";
        assertEquals(404, req("POST", "/agent/v1/actions/" + id + "/result", "rpgquest-staging", STG_TOKEN, body).statusCode());
    }

    @Test
    void wrongMethodIsRejected() throws Exception {
        assertEquals(405, req("GET", "/agent/v1/heartbeat", "rpgquest-dev", DEV_TOKEN, null).statusCode());
        assertEquals(405, req("POST", "/agent/v1/actions", "rpgquest-dev", DEV_TOKEN, "{}").statusCode());
    }
}
