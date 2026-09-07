package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Client HTTPS sortant de l'agent (issue #51) : contrat de requête + lecture bornée des réponses. */
class PlugAdminClientTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> seenAuth = new ConcurrentLinkedQueue<>();
    private volatile String actionsBody = "{\"actions\":[]}";
    private volatile int actionsStatus = 200;
    private volatile String oversizedBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/v1/heartbeat", ex -> {
            seenAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            respond(ex, 200, "{\"status\":\"ok\"}");
        });
        server.createContext("/agent/v1/actions", ex -> {
            if (ex.getRequestURI().getPath().endsWith("/result")) {
                respond(ex, 200, "{\"ok\":true}");
                return;
            }
            if (oversizedBody != null) {
                respond(ex, 200, oversizedBody);
                return;
            }
            respond(ex, actionsStatus, actionsBody);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AgentConfig config() {
        int port = server.getAddress().getPort();
        return new AgentConfig(true, "http://127.0.0.1:" + port, "rpgquest-dev", "dev", "secret-token",
                20, 15, 2000, 4000, 300, true, 2048);
    }

    private PlugAdminClient client() {
        return new PlugAdminClient(config());
    }

    @Test
    void heartbeatSendsBearerTokenAndReturnsStatus() {
        int status = client().sendHeartbeat(Map.of("protocol", "agent/v1", "agent_id", "rpgquest-dev"));
        assertEquals(200, status);
        assertEquals("Bearer secret-token", seenAuth.peek());
    }

    @Test
    void fetchActionsParsesTheQueue() {
        actionsBody = "{\"actions\":[{\"id\":\"a1\",\"type\":\"player.variable.get\","
                + "\"params\":{\"player\":\"Rondoudou9000\",\"key\":\"CLAIM_TIER_1\"}}]}";
        List<AgentAction> actions = client().fetchActions();
        assertEquals(1, actions.size());
        assertEquals("a1", actions.get(0).id());
        assertEquals("player.variable.get", actions.get(0).type());
        assertEquals("CLAIM_TIER_1", actions.get(0).param("key"));
    }

    @Test
    void fetchActionsRejectsNon200() {
        actionsStatus = 503;
        assertThrows(PlugAdminUnavailableException.class, () -> client().fetchActions());
    }

    @Test
    void oversizedResponseIsRejected() {
        oversizedBody = "x".repeat(5000); // > maxResponseBytes (2048)
        PlugAdminUnavailableException ex = assertThrows(PlugAdminUnavailableException.class,
                () -> client().fetchActions());
        assertTrue(ex.getMessage().contains("trop volumineuse"));
    }

    @Test
    void sendResultReturnsStatus() {
        int status = client().sendResult("a1", Map.of("action_id", "a1", "status", "SUCCESS"));
        assertEquals(200, status);
    }

    @Test
    void connectionRefusedBecomesUnavailable() {
        AgentConfig dead = new AgentConfig(true, "http://127.0.0.1:1", "rpgquest-dev", "dev", "t",
                20, 15, 500, 800, 300, true, 2048);
        assertThrows(PlugAdminUnavailableException.class,
                () -> new PlugAdminClient(dead).sendHeartbeat(Map.of("a", "b")));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
