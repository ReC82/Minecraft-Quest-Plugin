package com.lodygames.rpgquest.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Issue #421 : l'instantané part tel quel avec le jeton dédié ; un échec ne lève jamais d'exception. */
class SnapshotPushClientTest {

    private static SnapshotPushClient client(String baseUrl) {
        return new SnapshotPushClient(new SnapshotPushConfig(true, baseUrl, "jeton-instantane", 500, 2000),
                LoggerFactory.getLogger("test"));
    }

    @Test
    void sendsTheSnapshotVerbatimWithTheDedicatedToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/snapshot", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            SnapshotPushClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

            assertEquals(SnapshotPushClient.Outcome.STORED, client.push("{\"schemaVersion\":2,\"nom\":\"Café ☕\"}"));
            assertEquals("Bearer jeton-instantane", auth.get());
            assertEquals("{\"schemaVersion\":2,\"nom\":\"Café ☕\"}", body.get());

            status.set(401);
            assertEquals(SnapshotPushClient.Outcome.REJECTED, client.push("{}"));

            status.set(502);
            assertEquals(SnapshotPushClient.Outcome.UNAVAILABLE, client.push("{}"), "nginx sans portail : passager");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachablePortalIsReportedNotThrown() {
        assertEquals(SnapshotPushClient.Outcome.UNAVAILABLE, client("http://127.0.0.1:1").push("{}"));
    }
}
